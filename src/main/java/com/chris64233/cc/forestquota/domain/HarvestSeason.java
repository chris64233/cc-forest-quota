package com.chris64233.cc.forestquota.domain;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "harvest_season",
        check = @CheckConstraint(name = "ck_season_date_range", constraint = "end_date > start_date"))
public class HarvestSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "forest_area", nullable = false, length = 128)
    private String forestArea;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    protected HarvestSeason() {
    }

    public HarvestSeason(String forestArea, LocalDate startDate, LocalDate endDate) {
        this.forestArea = forestArea;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public Long getId() {
        return id;
    }

    public String getForestArea() {
        return forestArea;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }
}
