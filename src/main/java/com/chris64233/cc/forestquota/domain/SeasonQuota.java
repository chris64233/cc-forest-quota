package com.chris64233.cc.forestquota.domain;

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
import org.hibernate.annotations.Check;

import java.math.BigDecimal;

@Entity
@Table(name = "season_quota", uniqueConstraints =
        @UniqueConstraint(name = "uk_quota_season_species", columnNames = {"season_id", "species"}))
@Check(constraints = "authorized_volume > 0 "
        + "AND reserved_volume >= 0 AND harvested_volume >= 0 "
        + "AND reserved_volume + harvested_volume <= authorized_volume")
public class SeasonQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(nullable = false, length = 64)
    private String species;

    @Column(nullable = false, precision = 19, scale = Volumes.SCALE)
    private BigDecimal authorizedVolume;

    @Column(nullable = false, precision = 19, scale = Volumes.SCALE)
    private BigDecimal reservedVolume = BigDecimal.ZERO.setScale(Volumes.SCALE);

    @Column(nullable = false, precision = 19, scale = Volumes.SCALE)
    private BigDecimal harvestedVolume = BigDecimal.ZERO.setScale(Volumes.SCALE);

    protected SeasonQuota() {
    }

    public SeasonQuota(Season season, String species, BigDecimal authorizedVolume) {
        this.season = season;
        this.species = species;
        this.authorizedVolume = Volumes.normalize(authorizedVolume);
    }

    public Long getId() {
        return id;
    }

    public Season getSeason() {
        return season;
    }

    public String getSpecies() {
        return species;
    }

    public BigDecimal getAuthorizedVolume() {
        return authorizedVolume;
    }

    public BigDecimal getReservedVolume() {
        return reservedVolume;
    }

    public BigDecimal getHarvestedVolume() {
        return harvestedVolume;
    }

    public BigDecimal getAvailableVolume() {
        return authorizedVolume.subtract(reservedVolume).subtract(harvestedVolume);
    }

    public void reserve(BigDecimal volume) {
        reservedVolume = reservedVolume.add(volume);
    }

    public void settle(BigDecimal approvedVolume, BigDecimal actualVolume) {
        reservedVolume = reservedVolume.subtract(approvedVolume);
        harvestedVolume = harvestedVolume.add(actualVolume);
    }
}
