package com.chris64233.cc.forestquota.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

@Entity
@Table(name = "season", uniqueConstraints =
        @UniqueConstraint(name = "uk_season_area_code", columnNames = {"forest_area", "season_code"}))
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "forest_area", nullable = false, length = 64)
    private String forestArea;

    @Column(name = "season_code", nullable = false, length = 64)
    private String seasonCode;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    protected Season() {
    }

    public Season(String forestArea, String seasonCode, LocalDate startDate, LocalDate endDate) {
        this.forestArea = forestArea;
        this.seasonCode = seasonCode;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public String getForestArea() {
        return forestArea;
    }

    public String getSeasonCode() {
        return seasonCode;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean covers(LocalDate from, LocalDate to) {
        return !from.isBefore(startDate) && !to.isAfter(endDate);
    }
}
