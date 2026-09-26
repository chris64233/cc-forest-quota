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
@Table(name = "license_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_item_license_species", columnNames = {"license_id", "species"}),
        check = {
                @CheckConstraint(name = "ck_item_approved_positive", constraint = "approved_volume > 0"),
                @CheckConstraint(name = "ck_item_actual_nonneg", constraint = "actual_volume IS NULL OR actual_volume >= 0"),
                @CheckConstraint(name = "ck_item_released_nonneg", constraint = "released_volume IS NULL OR released_volume >= 0")
        })
public class LicenseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "license_id", nullable = false)
    private HarvestLicense license;

    @Column(name = "species", nullable = false, length = 64)
    private String species;

    @Column(name = "approved_volume", nullable = false, precision = 19, scale = 3)
    private BigDecimal approvedVolume;

    @Column(name = "actual_volume", precision = 19, scale = 3)
    private BigDecimal actualVolume;

    @Column(name = "released_volume", precision = 19, scale = 3)
    private BigDecimal releasedVolume;

    protected LicenseItem() {
    }

    public LicenseItem(String species, BigDecimal approvedVolume) {
        this.species = species;
        this.approvedVolume = approvedVolume;
    }

    public void settle(BigDecimal actualVolume) {
        this.actualVolume = actualVolume;
        this.releasedVolume = this.approvedVolume.subtract(actualVolume);
    }

    public BigDecimal remainingVolume() {
        BigDecimal actual = actualVolume == null ? BigDecimal.ZERO : actualVolume;
        BigDecimal released = releasedVolume == null ? BigDecimal.ZERO : releasedVolume;
        return approvedVolume.subtract(actual).subtract(released);
    }

    public void releaseRemaining() {
        BigDecimal released = releasedVolume == null ? BigDecimal.ZERO : releasedVolume;
        this.releasedVolume = released.add(remainingVolume());
    }

    void setLicense(HarvestLicense license) {
        this.license = license;
    }

    public Long getId() {
        return id;
    }

    public HarvestLicense getLicense() {
        return license;
    }

    public String getSpecies() {
        return species;
    }

    public BigDecimal getApprovedVolume() {
        return approvedVolume;
    }

    public BigDecimal getActualVolume() {
        return actualVolume;
    }

    public BigDecimal getReleasedVolume() {
        return releasedVolume;
    }
}
