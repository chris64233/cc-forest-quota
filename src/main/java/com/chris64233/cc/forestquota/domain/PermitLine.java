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
@Table(name = "permit_line", uniqueConstraints =
        @UniqueConstraint(name = "uk_permit_line_species", columnNames = {"permit_id", "species"}))
@Check(constraints = "approved_volume > 0 AND (harvested_volume IS NULL OR harvested_volume >= 0)")
public class PermitLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Column(nullable = false, length = 64)
    private String species;

    @Column(nullable = false, precision = 19, scale = Volumes.SCALE)
    private BigDecimal approvedVolume;

    @Column(precision = 19, scale = Volumes.SCALE)
    private BigDecimal harvestedVolume;

    protected PermitLine() {
    }

    public PermitLine(Permit permit, String species, BigDecimal approvedVolume) {
        this.permit = permit;
        this.species = species;
        this.approvedVolume = Volumes.normalize(approvedVolume);
    }

    public Long getId() {
        return id;
    }

    public String getSpecies() {
        return species;
    }

    public BigDecimal getApprovedVolume() {
        return approvedVolume;
    }

    public BigDecimal getHarvestedVolume() {
        return harvestedVolume;
    }

    public void settle(BigDecimal actualVolume) {
        this.harvestedVolume = Volumes.normalize(actualVolume);
    }

    public BigDecimal getReleasedVolume() {
        if (harvestedVolume == null) {
            return null;
        }
        return approvedVolume.subtract(harvestedVolume);
    }
}
