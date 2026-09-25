package com.chris64233.cc.forestquota.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditEventType eventType;

    @Column(nullable = false, length = 512)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(Permit permit, AuditEventType eventType, String detail) {
        this.permit = permit;
        this.eventType = eventType;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
