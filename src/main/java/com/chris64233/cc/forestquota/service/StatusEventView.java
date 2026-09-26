package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.LicenseStatusEvent;

import java.time.Instant;

public record StatusEventView(Long eventNo, AuditEventType eventType, String reason,
                              Instant effectiveAt, Instant createdAt) {

    public static StatusEventView of(LicenseStatusEvent event) {
        return new StatusEventView(event.getEventNo(), event.getEventType(), event.getReason(),
                event.getEffectiveAt(), event.getCreatedAt());
    }
}
