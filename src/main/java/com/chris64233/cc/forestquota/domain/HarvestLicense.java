package com.chris64233.cc.forestquota.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CheckConstraint;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "harvest_license",
        uniqueConstraints = @UniqueConstraint(name = "uk_license_application_no", columnNames = "application_no"),
        check = @CheckConstraint(name = "ck_license_work_dates", constraint = "work_end_date >= work_start_date"))
public class HarvestLicense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_no", nullable = false, length = 64)
    private String applicationNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private HarvestSeason season;

    @Column(name = "work_start_date", nullable = false)
    private LocalDate workStartDate;

    @Column(name = "work_end_date", nullable = false)
    private LocalDate workEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LicenseStatus status = LicenseStatus.APPROVED;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "settlement_hash", length = 64)
    private String settlementHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @OneToMany(mappedBy = "license", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LicenseItem> items = new ArrayList<>();

    protected HarvestLicense() {
    }

    public HarvestLicense(String applicationNo, HarvestSeason season, LocalDate workStartDate,
                          LocalDate workEndDate, String contentHash, Instant createdAt) {
        this.applicationNo = applicationNo;
        this.season = season;
        this.workStartDate = workStartDate;
        this.workEndDate = workEndDate;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
    }

    public void addItem(LicenseItem item) {
        items.add(item);
        item.setLicense(this);
    }

    public void markSettled(String settlementHash, Instant settledAt) {
        this.status = LicenseStatus.SETTLED;
        this.settlementHash = settlementHash;
        this.settledAt = settledAt;
    }

    public Long getId() {
        return id;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public HarvestSeason getSeason() {
        return season;
    }

    public LocalDate getWorkStartDate() {
        return workStartDate;
    }

    public LocalDate getWorkEndDate() {
        return workEndDate;
    }

    public LicenseStatus getStatus() {
        return status;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getSettlementHash() {
        return settlementHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public List<LicenseItem> getItems() {
        return items;
    }
}
