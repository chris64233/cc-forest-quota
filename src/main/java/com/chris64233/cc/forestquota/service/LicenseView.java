package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.HarvestLicense;
import com.chris64233.cc.forestquota.domain.LicenseStatus;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record LicenseView(Long licenseId, String applicationNo, Long seasonId, String forestArea,
                          LocalDate workStartDate, LocalDate workEndDate, LicenseStatus status,
                          List<LicenseItemView> items) {

    public static LicenseView of(HarvestLicense license) {
        List<LicenseItemView> items = license.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getSpecies()))
                .map(item -> new LicenseItemView(item.getSpecies(), item.getApprovedVolume(),
                        item.getActualVolume(), item.getReleasedVolume()))
                .toList();
        return new LicenseView(license.getId(), license.getApplicationNo(), license.getSeason().getId(),
                license.getSeason().getForestArea(), license.getWorkStartDate(), license.getWorkEndDate(),
                license.getStatus(), items);
    }
}
