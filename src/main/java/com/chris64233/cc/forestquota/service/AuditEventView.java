package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.AuditEvent;
import com.chris64233.cc.forestquota.domain.AuditEventType;

import java.time.Instant;

public record AuditEventView(AuditEventType eventType, String species, String details, Instant createdAt) {

    public static AuditEventView of(AuditEvent event) {
        return new AuditEventView(event.getEventType(), event.getSpecies(), event.getDetails(), event.getCreatedAt());
    }
}
