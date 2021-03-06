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

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public class TypeKeyValueIdentity implements Identity {
    private final Class<?> type;
    private final SortedMap<String, String> identityFieldToValue;

    public TypeKeyValueIdentity(Class<?> type, Map<String, String> identityFieldToValue) {
        this.type = type;
        this.identityFieldToValue = new TreeMap<>(identityFieldToValue);
    }

    @Override
    public String getResourceId() {
        return toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypeKeyValueIdentity identity = (TypeKeyValueIdentity) o;
        return Objects.equals(identityFieldToValue, identity.identityFieldToValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identityFieldToValue);
    }

    @Override
    public String toString() {
        return "TypeKeyValueIdentity{" +
                "type=" + type +
                ", identityFieldToValue=" + identityFieldToValue +
                '}';
    }
}
