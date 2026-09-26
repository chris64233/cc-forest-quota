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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "license_status_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_status_event_license_no",
                columnNames = {"license_id", "event_no"}))
public class LicenseStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "license_id", nullable = false)
    private HarvestLicense license;

    @Column(name = "event_no", nullable = false)
    private long eventNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private AuditEventType eventType;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LicenseStatusEvent() {
    }

    public LicenseStatusEvent(HarvestLicense license, long eventNo, AuditEventType eventType,
                              String reason, Instant effectiveAt, Instant createdAt) {
        this.license = license;
        this.eventNo = eventNo;
        this.eventType = eventType;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public HarvestLicense getLicense() {
        return license;
    }

    public long getEventNo() {
        return eventNo;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public String getReason() {
        return reason;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
