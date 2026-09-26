package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.LicenseStatusEvent;
import com.chris64233.cc.forestquota.domain.StatusEventType;

import java.time.Instant;

public record StatusEventView(String eventNo, StatusEventType eventType, String reason,
                              Instant effectiveAt, Instant createdAt) {

    public static StatusEventView of(LicenseStatusEvent event) {
        return new StatusEventView(event.getEventNo(), event.getEventType(), event.getReason(),
                event.getEffectiveAt(), event.getCreatedAt());
    }
}
