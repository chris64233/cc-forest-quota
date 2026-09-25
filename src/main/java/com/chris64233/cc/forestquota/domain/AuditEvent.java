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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "license_id")
    private HarvestLicense license;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private AuditEventType eventType;

    @Column(name = "species", length = 64)
    private String species;

    @Column(name = "details", nullable = false, length = 512)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(HarvestLicense license, Long seasonId, AuditEventType eventType,
                      String species, String details, Instant createdAt) {
        this.license = license;
        this.seasonId = seasonId;
        this.eventType = eventType;
        this.species = species;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public HarvestLicense getLicense() {
        return license;
    }

    public Long getSeasonId() {
        return seasonId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public String getSpecies() {
        return species;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
