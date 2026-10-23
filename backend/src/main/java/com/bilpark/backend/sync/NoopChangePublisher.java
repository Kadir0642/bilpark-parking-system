package com.bilpark.backend.sync;

import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.SyncEventType;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Placeholder until the SSE implementation lands; replaced by SyncService. */
@Component
public class NoopChangePublisher implements ChangePublisher {
    @Override
    public void zoneEvent(SyncEventType type, Long zoneId, StreetLocation street, Map<String, Object> payload) {}

    @Override
    public void globalEvent(SyncEventType type, Map<String, Object> payload) {}

    @Override
    public void userEvent(SyncEventType type, Long targetUserId, Map<String, Object> payload) {}
}
