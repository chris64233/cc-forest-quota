package com.chris64233.cc.forestquota.web.dto;

import com.chris64233.cc.forestquota.domain.HarvestSeason;

import java.time.LocalDate;

public record SeasonResponse(Long id, String forestArea, LocalDate startDate, LocalDate endDate) {

    public static SeasonResponse of(HarvestSeason season) {
        return new SeasonResponse(season.getId(), season.getForestArea(),
                season.getStartDate(), season.getEndDate());
    }
}
