/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.operation;

import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.core.EntryView;
import com.hazelcast.core.ManagedContext;
import com.hazelcast.map.EntryBackupProcessor;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.LocalMapStatsProvider;
import com.hazelcast.map.MapContainer;
import com.hazelcast.map.MapEntrySimple;
import com.hazelcast.map.MapEventPublisher;
import com.hazelcast.map.MapServiceContext;
import com.hazelcast.map.record.Record;
import com.hazelcast.monitor.impl.LocalMapStatsImpl;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.spi.BackupAwareOperation;
import com.hazelcast.spi.EventService;
import com.hazelcast.spi.Operation;
import com.hazelcast.util.Clock;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Map;

import static com.hazelcast.map.EntryViews.createSimpleEntryView;

/**
 * GOTCHA : This operation LOADS missing keys from map-store, in contrast with PartitionWideEntryOperation.
 */
public class EntryOperation extends LockAwareOperation implements BackupAwareOperation {

    protected Object oldValue;
    private EntryProcessor entryProcessor;
    private EntryEventType eventType;
    private Object response;


    public EntryOperation() {
    }

    public EntryOperation(String name, Data dataKey, EntryProcessor entryProcessor) {
        super(name, dataKey);
        this.entryProcessor = entryProcessor;
    }

    @Override
    public void innerBeforeRun() {
        final SerializationService serializationService = getNodeEngine().getSerializationService();
        final ManagedContext managedContext = serializationService.getManagedContext();
        managedContext.initialize(entryProcessor);
    }

    @Override
    public void run() {
        final long now = getNow();

        oldValue = getValueFor(dataKey);

        final Object key = toObject(dataKey);
        final Object value = toObject(oldValue);

        final Map.Entry entry = createMapEntry(key, value);

        response = process(entry);

        if (noOp(entry)) {
            return;
        }

        if (entryRemoved(entry, now)) {
            return;
        }

        if (entryAddedOrUpdated(entry, now)) {
            return;
        }
    }

    @Override
    public void afterRun() throws Exception {
        super.afterRun();
        if (eventType == null) {
            return;
        }
        invalidateNearCaches();
        publishEntryEvent();
        publishWanReplicationEvent();
    }

    @Override
    public void onWaitExpire() {
        getResponseHandler().sendResponse(null);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        entryProcessor = in.readObject();
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeObject(entryProcessor);
    }

    @Override
    public Object getResponse() {
        return response;
    }

    @Override
    public String toString() {
        return "EntryOperation{}";
    }

    @Override
    public Operation getBackupOperation() {
        EntryBackupProcessor backupProcessor = entryProcessor.getBackupProcessor();
        return backupProcessor != null ? new EntryBackupOperation(name, dataKey, backupProcessor) : null;
    }

    @Override
    public boolean shouldBackup() {
        return entryProcessor.getBackupProcessor() != null;
    }

    @Override
    public int getAsyncBackupCount() {
        return mapContainer.getAsyncBackupCount();
    }

    @Override
    public int getSyncBackupCount() {
        return mapContainer.getBackupCount();
    }

    private long getLatencyFrom(long begin) {
        return Clock.currentTimeMillis() - begin;
    }

    private Object toObject(Object data) {
        final MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        return mapServiceContext.toObject(data);
    }

    private Data toData(Object obj) {
        final MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        return mapServiceContext.toData(obj);
    }

    private long getNow() {
        return Clock.currentTimeMillis();
    }

    /**
     * Entry does not exist and no add operation is done.
     */
    private boolean noOp(Map.Entry entry) {
        return oldValue == null && entry.getValue() == null;
    }

    private boolean entryRemoved(Map.Entry entry, long now) {
        final Object value = entry.getValue();
        if (value == null) {
            recordStore.remove(dataKey);
            getLocalMapStats().incrementRemoves(getLatencyFrom(now));
            eventType = pickEvent(value);
            return true;
        }
        return false;
    }

    /**
     * Only difference between add and update is event type to be published.
     */
    private boolean entryAddedOrUpdated(Map.Entry entry, long now) {
        final Object value = entry.getValue();
        if (value != null) {
            put(value);
            getLocalMapStats().incrementPuts(getLatencyFrom(now));
            dataValue = toData(value);
            eventType = pickEvent(value);
            return true;
        }
        return false;
    }

    private EntryEventType pickEvent(Object value) {
        if (oldValue == null && value != null) {
            return EntryEventType.ADDED;
        } else if (oldValue != null && value != null) {
            return EntryEventType.UPDATED;
        } else if (value == null) {
            return EntryEventType.REMOVED;
        }
        throw new RuntimeException("Unexpected event type. This should not be happen.");
    }

    private void put(Object value) {
        recordStore.put(new AbstractMap.SimpleImmutableEntry<Data, Object>(dataKey, value));
    }


    private Object getValueFor(Data dataKey) {
        return recordStore.getMapEntry(dataKey).getValue();
    }

    private Data process(Map.Entry entry) {
        final Object result = entryProcessor.process(entry);
        return toData(result);
    }

    private Map.Entry createMapEntry(Object key, Object value) {
        return new MapEntrySimple(key, value);
    }

    private LocalMapStatsImpl getLocalMapStats() {
        final MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        final LocalMapStatsProvider localMapStatsProvider = mapServiceContext.getLocalMapStatsProvider();
        return localMapStatsProvider.getLocalMapStatsImpl(name);
    }

    private boolean hasRegisteredListenerForThisMap() {
        final String serviceName = mapService.getMapServiceContext().serviceName();
        final EventService eventService = getNodeEngine().getEventService();
        return eventService.hasEventRegistration(serviceName, name);
    }

    /**
     * Nullify old value if in memory format is object and operation is not removal
     * since old and new value in fired event {@link com.hazelcast.core.EntryEvent}
     * may be same due to the object in memory format.
     */
    private void nullifyOldValueIfNecessary() {
        final MapConfig mapConfig = mapContainer.getMapConfig();
        final InMemoryFormat format = mapConfig.getInMemoryFormat();
        if (format == InMemoryFormat.OBJECT && eventType != EntryEventType.REMOVED) {
            oldValue = null;
        }
    }

    private void publishEntryEvent() {
        if (hasRegisteredListenerForThisMap()) {
            nullifyOldValueIfNecessary();
            final MapEventPublisher mapEventPublisher = getMapEventPublisher();
            mapEventPublisher.
                    publishEvent(getCallerAddress(), name, eventType, dataKey, toData(oldValue), dataValue);
        }
    }

    private void publishWanReplicationEvent() {
        final MapContainer mapContainer = this.mapContainer;
        if (mapContainer.getWanReplicationPublisher() == null
                && mapContainer.getWanMergePolicy() == null) {
            return;
        }
        final MapEventPublisher mapEventPublisher = getMapEventPublisher();
        final Data key = dataKey;

        if (EntryEventType.REMOVED.equals(eventType)) {
            mapEventPublisher.publishWanReplicationRemove(name, key, getNow());
        } else {
            final Record record = recordStore.getRecord(key);
            if (record != null) {
                final Data dataValueAsData = toData(dataValue);
                final EntryView entryView = createSimpleEntryView(key, dataValueAsData, record);
                mapEventPublisher.publishWanReplicationUpdate(name, entryView);
            }
        }
    }

    private MapEventPublisher getMapEventPublisher() {
        final MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        return mapServiceContext.getMapEventPublisher();
    }

}
