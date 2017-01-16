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

package org.esbtools.eventhandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.camel.builder.RouteBuilder;

import com.google.common.util.concurrent.Futures;

public class PollingNotificationProcessorRoute extends RouteBuilder {
    private final NotificationRepository notificationRepository;
    private final DocumentEventRepository documentEventRepository;
    private final Duration pollingInterval;
    private final Duration notificationProcessTimeout;
    private final int batchSize;

    private static final AtomicInteger idCounter = new AtomicInteger(1);
    private final int id = idCounter.getAndIncrement();

    public PollingNotificationProcessorRoute(NotificationRepository notificationRepository,
            DocumentEventRepository documentEventRepository, Duration pollingInterval,
            Duration notificationProcessTimeout, int batchSize) {
        this.notificationRepository = notificationRepository;
        this.documentEventRepository = documentEventRepository;
        this.pollingInterval = pollingInterval;
        this.batchSize = batchSize;
        this.notificationProcessTimeout = Objects.requireNonNull(notificationProcessTimeout,
                "notificationProcessTimeout");
    }

    @Override
    public void configure() throws Exception {
        from("timer:pollForNotifications" + id + "?period=" + pollingInterval.toMillis())
        .routeId("notificationProcessor-" + id)
        .process(exchange -> {
            List<? extends Notification> notifications =
                    notificationRepository.retrieveOldestNotificationsUpTo(batchSize);
            Map<Notification, Future<Collection<DocumentEvent>>> notificationsToFutureEvents =
                    new HashMap<>(notifications.size());

            // Intentionally cache all futures before waiting for any.
            for (Notification notification : notifications) {
                try {
                    Future<Collection<DocumentEvent>> futureEvents = notification.toDocumentEvents();
                    notificationsToFutureEvents.put(notification, futureEvents);
                } catch (Exception e) {
                    log.error("Failed to get future document events for notification: " +
                            notification, e);
                    notificationsToFutureEvents.put(notification, Futures.immediateFailedFuture(e));
                }
            }

            Map<Notification, Collection<DocumentEvent>> notificationsToDocumentEvents =
                    new HashMap<>();
            List<FailedNotification> failedNotifications = new ArrayList<>();

            for (Entry<Notification, Future<Collection<DocumentEvent>>> notificationToFutureEvents
                    : notificationsToFutureEvents.entrySet()) {
                Notification notification = notificationToFutureEvents.getKey();
                Future<Collection<DocumentEvent>> futureEvents =
                        notificationToFutureEvents.getValue();
                try {
                    Collection<DocumentEvent> events = futureEvents.get(notificationProcessTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    notificationsToDocumentEvents.put(notification, events);
                } catch (ExecutionException | InterruptedException e) {
                    log.error("Failed to get document events for notification: " + notification, e);
                    failedNotifications.add(new FailedNotification(notification, e));
                }
            }

            Iterator<Entry<Notification, Collection<DocumentEvent>>> notificationsToEventsIterator =
                    notificationsToDocumentEvents.entrySet().iterator();
            while (notificationsToEventsIterator.hasNext()) {
                Entry<Notification, Collection<DocumentEvent>> notificationToEvents =
                        notificationsToEventsIterator.next();
                try {
                    notificationRepository.ensureTransactionActive(notificationToEvents.getKey());
                } catch (Exception e) {
                    notificationsToEventsIterator.remove();
                    if (log.isWarnEnabled()) {
                        log.warn("Notification transaction no longer active, not processing: " +
                                notificationToEvents.getKey(), e);
                    }
                }
            }

            List<DocumentEvent> documentEvents = notificationsToDocumentEvents.values()
                    .stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());

            log.debug("Persisting {} document events via route {}: {}",
                    documentEvents.size(), exchange.getFromRouteId(), documentEvents);

            try {
                documentEventRepository.addNewDocumentEvents(documentEvents);
            } catch (Exception e) {
                log.error("Failed to persist new document events from notifications. Rolling " +
                        "back processing. Document events were: " + documentEvents, e);
                notificationsToDocumentEvents.clear();
            }

            notificationRepository.markNotificationsProcessedOrFailed(
                    notificationsToDocumentEvents.keySet(), failedNotifications);
        });
    }
}
