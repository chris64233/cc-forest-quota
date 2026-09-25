package com.chris64233.cc.forestquota.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "permit", uniqueConstraints =
        @UniqueConstraint(name = "uk_permit_application_no", columnNames = "application_no"))
public class Permit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_no", nullable = false, length = 64)
    private String applicationNo;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(nullable = false)
    private LocalDate workStartDate;

    @Column(nullable = false)
    private LocalDate workEndDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PermitStatus status = PermitStatus.APPROVED;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "permit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("species ASC")
    private List<PermitLine> lines = new ArrayList<>();

    protected Permit() {
    }

    public Permit(String applicationNo, Season season, LocalDate workStartDate, LocalDate workEndDate) {
        this.applicationNo = applicationNo;
        this.season = season;
        this.workStartDate = workStartDate;
        this.workEndDate = workEndDate;
    }

    public void addLine(PermitLine line) {
        lines.add(line);
    }

    public Long getId() {
        return id;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public Season getSeason() {
        return season;
    }

    public LocalDate getWorkStartDate() {
        return workStartDate;
    }

    public LocalDate getWorkEndDate() {
        return workEndDate;
    }

    public PermitStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PermitLine> getLines() {
        return lines;
    }

    public void markSettled() {
        this.status = PermitStatus.SETTLED;
    }
}
