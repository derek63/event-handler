/*
 *  Copyright 2016 esbtools Contributors and/or its affiliates.
 *
 *  This file is part of esbtools.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.esbtools.eventhandler.lightblue;

import org.esbtools.eventhandler.FailedNotification;
import org.esbtools.eventhandler.Notification;
import org.esbtools.eventhandler.NotificationRepository;
import org.esbtools.eventhandler.lightblue.client.BulkLightblueRequester;
import org.esbtools.eventhandler.lightblue.client.FindRequests;
import org.esbtools.eventhandler.lightblue.client.LightblueErrors;
import org.esbtools.eventhandler.lightblue.client.LightblueRequester;
import org.esbtools.eventhandler.lightblue.client.UpdateRequests;
import org.esbtools.lightbluenotificationhook.NotificationEntity;

import com.redhat.lightblue.client.LightblueClient;
import com.redhat.lightblue.client.LightblueException;
import com.redhat.lightblue.client.request.DataBulkRequest;
import com.redhat.lightblue.client.response.LightblueBulkDataResponse;
import com.redhat.lightblue.client.response.LightblueBulkResponseException;
import com.redhat.lightblue.client.response.LightblueDataResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A notification repository which uses lightblue as the notification store. Notifications are
 * persisted in the form of {@link NotificationEntity} which must be configured as an entity in
 * your lightblue instance. Notifications can be written using the
 * <a href="https://github.com/esbtools/lightblue-notification-hook">lightblue notification hook</a>.
 */
public class LightblueNotificationRepository implements NotificationRepository {
    private final LightblueClient lightblue;
    private final LightblueNotificationRepositoryConfig config;
    private final Map<String, NotificationFactory> notificationFactoryByEntityName;
    private final Clock clock;

    private final Set<String> supportedEntityNames;
    /** Cached to avoid extra garbage. */
    private final String[] supportedEntityNamesArray;

    private static final Logger logger = LoggerFactory.getLogger(LightblueNotificationRepository.class);

    public LightblueNotificationRepository(LightblueClient lightblue,
            LightblueNotificationRepositoryConfig config,
            Map<String, NotificationFactory> notificationFactoryByEntityName, Clock clock) {
        this.lightblue = lightblue;
        this.config = config;
        this.notificationFactoryByEntityName = notificationFactoryByEntityName;
        this.clock = clock;

        supportedEntityNames = notificationFactoryByEntityName.keySet();
        supportedEntityNamesArray = supportedEntityNames.toArray(new String[supportedEntityNames.size()]);
    }

    @Override
    public List<LightblueNotification> retrieveOldestNotificationsUpTo(int maxNotifications)
            throws Exception {
        String[] entitiesToProcess = getSupportedAndEnabledEntityNames();
        Duration processingTimeout = config.getNotificationProcessingTimeout();

        if (entitiesToProcess.length == 0) {
            logger.info("Not retrieving any notifications because either there are no enabled " +
                    "or supported entity names to process. Supported entity names are {}. " +
                    "Of those, enabled entity names are {}",
                    supportedEntityNames, Arrays.toString(entitiesToProcess));
            return Collections.emptyList();
        }

        if (maxNotifications == 0) {
            return Collections.emptyList();
        }

        NotificationEntity[] notificationEntities = lightblue
                .data(FindRequests.oldestNotificationsForEntitiesUpTo(
                        entitiesToProcess, maxNotifications,
                        clock.instant().minus(processingTimeout)))
                .parseProcessed(NotificationEntity[].class);
        List<ProcessingNotification> processingList =
                ProcessingNotification.parseNotificationEntities(
                        notificationEntities,
                        new BulkLightblueRequester(lightblue),
                        notificationFactoryByEntityName, clock);

        if (processingList.isEmpty()) {
            return Collections.emptyList();
        }

        DataBulkRequest updateEntities = new DataBulkRequest();
        List<LightblueNotification> updatedNotifications = new ArrayList<>(processingList.size());

        for (ProcessingNotification processing : processingList) {

            updateEntities.add(UpdateRequests.notificationStatusIfCurrent(
                    processing.notification.wrappedNotificationEntity(),
                    processing.originalProcessingDate));

            updatedNotifications.add(processing.notification);
        }

        LightblueBulkDataResponse bulkResponse;

        try {
            bulkResponse = lightblue.bulkData(updateEntities);
        } catch (LightblueBulkResponseException e) {
            // If some failed, that's okay. We have to iterate through responses either way.
            // We'll check for errors then.
            bulkResponse = e.getBulkResponse();
        }

        Iterator<LightblueNotification> notificationsIterator = updatedNotifications.iterator();
        Iterator<LightblueDataResponse> responsesIterator = bulkResponse.getResponses().iterator();

        while (notificationsIterator.hasNext()) {
            if (!responsesIterator.hasNext()) {
                throw new IllegalStateException("Mismatched number of requests and responses! " +
                        "Notifications looked like: " + updatedNotifications +
                        "Responses looked like: " + bulkResponse.getResponses());
            }

            LightblueDataResponse response = responsesIterator.next();
            LightblueNotification notification = notificationsIterator.next();

            if (LightblueErrors.arePresentInResponse(response)) {
                if (logger.isWarnEnabled()) {
                    List<String> errorStrings = LightblueErrors.toStringsFromErrorResponse(response);
                    logger.warn("Notification update failed. Will not process. " +
                            "Event was: <{}>. Errors: <{}>", notification, errorStrings);
                }
                notificationsIterator.remove();
                continue;
            }

            if (response.parseModifiedCount() == 0) {
                logger.warn("Notification updated by another thread. Will not process. " +
                        "Notification id: {}", notification.wrappedNotificationEntity().get_id());
                notificationsIterator.remove();
            }
        }

        return updatedNotifications;
    }


    @Override
    public void ensureTransactionActive(Notification notification) throws Exception {
        if (!(notification instanceof LightblueNotification)) {
            throw new IllegalArgumentException("Unknown event type. Only " +
                    "LightblueDocumentEvent is supported. Event type was: " +
                    notification.getClass());
        }

        LightblueNotification lightblueNotification = (LightblueNotification) notification;
        Duration processingTimeout = config.getNotificationProcessingTimeout();
        Duration expireThreshold = config.getNotificationExpireThreshold();

        Instant processingDate = lightblueNotification.wrappedNotificationEntity()
                .getProcessingDate().toInstant();
        Instant expireDate = processingDate.plus(processingTimeout).minus(expireThreshold);

        if (clock.instant().isAfter(expireDate)) {
            throw new ProcessingExpiredException(notification, processingTimeout, expireThreshold);
        }
    }

    @Override
    public void markNotificationsProcessedOrFailed(Collection<? extends Notification> notification,
            Collection<FailedNotification> failures) throws LightblueException {
        List<NotificationEntity> processedNotificationEntities = notification.stream()
                .map(LightblueNotificationRepository::asEntity)
                .peek(entity -> {
                    entity.setStatus(NotificationEntity.Status.processed);
                    entity.setProcessedDate(Date.from(clock.instant()));
                })
                .collect(Collectors.toList());

        List<NotificationEntity> failedNotificationEntities = failures.stream()
                .map(FailedNotification::notification)
                .map(LightblueNotificationRepository::asEntity)
                .peek(entity -> {
                    entity.setStatus(NotificationEntity.Status.failed);
                    entity.setProcessedDate(Date.from(clock.instant()));
                })
                .collect(Collectors.toList());

        DataBulkRequest markNotifications = new DataBulkRequest();
        markNotifications.addAll(
                UpdateRequests.notificationsStatusAndProcessedDate(processedNotificationEntities));
        markNotifications.addAll(
                UpdateRequests.notificationsStatusAndProcessedDate(failedNotificationEntities));

        if (markNotifications.getRequests().isEmpty()) {
            return;
        }

        // Let failures propagate
        lightblue.bulkData(markNotifications);
    }

    private String[] getSupportedAndEnabledEntityNames() {
        Set<String> entityNamesToProcess = config.getEntityNamesToProcess();

        if (entityNamesToProcess == null) {
            return new String[0];
        }

        if (entityNamesToProcess.containsAll(supportedEntityNames)) {
            return supportedEntityNamesArray;
        }

        List<String> supportedAndEnabled = new ArrayList<>(supportedEntityNames);
        supportedAndEnabled.retainAll(entityNamesToProcess);
        return supportedAndEnabled.toArray(new String[supportedAndEnabled.size()]);
    }

    private static NotificationEntity asEntity(Notification notification) {
        if (notification instanceof LightblueNotification) {
            return ((LightblueNotification) notification).wrappedNotificationEntity();
        }

        throw new IllegalArgumentException("Unknown notification type. Only " +
                "LightblueNotification is supported. Event type was: " + notification.getClass());
    }

    static class ProcessingNotification {
        final String notificationId;
        final Date originalProcessingDate;
        final LightblueNotification notification;

        static List<ProcessingNotification> parseNotificationEntities(
                NotificationEntity[] entities,
                LightblueRequester requester,
                Map<String, ? extends NotificationFactory> notificationFactoriesByEntityName,
                Clock clock) {
            List<ProcessingNotification> processedNotifications =
                    new ArrayList<>(entities.length);

            List<NotificationEntity> notificationEntityList = Arrays.asList(entities);

            for (NotificationEntity entity : notificationEntityList) {
                LightblueNotification notification;

                try {
                    notification = notificationFactoriesByEntityName
                            .get(entity.getEntityName())
                            .getNotificationForEntity(entity, requester);
                } catch (Exception e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Failed to parse notification entity: " + entity, e);
                    }

                    notification = new UnparseableNotification(e, entity);
                }

                Date originalProcessingDate = entity.getProcessingDate();

                ProcessingNotification processing =
                        new ProcessingNotification(entity.get_id(), notification,
                                originalProcessingDate);

                processedNotifications.add(processing);
                entity.setProcessingDate(Date.from(clock.instant()));
                entity.setStatus(NotificationEntity.Status.processing);

            }

            return processedNotifications;
        }

        private ProcessingNotification(String notificationId, LightblueNotification notification,
                Date originalProcessingDate) {
            this.notificationId = notificationId;
            this.notification = notification;
            this.originalProcessingDate = originalProcessingDate;
        }

        @Override
        public String toString() {
            return "ProcessingNotification{" +
                    "originalProcessingDate=" + originalProcessingDate +
                    ", notification=" + notification +
                    ", notificationId='" + notificationId + '\'' +
                    '}';
        }
    }
}
