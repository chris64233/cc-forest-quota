package com.chris64233.cc.forestquota.domain;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "season_quota",
        uniqueConstraints = @UniqueConstraint(name = "uk_quota_season_species", columnNames = {"season_id", "species"}),
        check = {
                @CheckConstraint(name = "ck_quota_authorized_positive", constraint = "authorized_volume > 0"),
                @CheckConstraint(name = "ck_quota_occupied_nonneg", constraint = "occupied_volume >= 0"),
                @CheckConstraint(name = "ck_quota_harvested_nonneg", constraint = "harvested_volume >= 0"),
                @CheckConstraint(name = "ck_quota_within_authorized", constraint = "occupied_volume + harvested_volume <= authorized_volume")
        })
public class SeasonQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private HarvestSeason season;

    @Column(name = "species", nullable = false, length = 64)
    private String species;

    @Column(name = "authorized_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal authorizedVolume;

    @Column(name = "occupied_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal occupiedVolume = BigDecimal.ZERO.setScale(3);

    @Column(name = "harvested_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal harvestedVolume = BigDecimal.ZERO.setScale(3);

    protected SeasonQuota() {
    }

    public SeasonQuota(HarvestSeason season, String species, BigDecimal authorizedVolume) {
        this.season = season;
        this.species = species;
        this.authorizedVolume = authorizedVolume;
    }

    public BigDecimal available() {
        return authorizedVolume.subtract(occupiedVolume).subtract(harvestedVolume);
    }

    public void occupy(BigDecimal amount) {
        this.occupiedVolume = this.occupiedVolume.add(amount);
    }

    public void settle(BigDecimal approved, BigDecimal actual) {
        this.occupiedVolume = this.occupiedVolume.subtract(approved);
        this.harvestedVolume = this.harvestedVolume.add(actual);
    }

    public void release(BigDecimal amount) {
        this.occupiedVolume = this.occupiedVolume.subtract(amount);
    }

    public Long getId() {
        return id;
    }

    public HarvestSeason getSeason() {
        return season;
    }

    public String getSpecies() {
        return species;
    }

    public BigDecimal getAuthorizedVolume() {
        return authorizedVolume;
    }

    public BigDecimal getOccupiedVolume() {
        return occupiedVolume;
    }

    public BigDecimal getHarvestedVolume() {
        return harvestedVolume;
    }
}
