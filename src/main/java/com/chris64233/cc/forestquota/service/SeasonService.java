package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.SeasonQuota;
import com.chris64233.cc.forestquota.repository.HarvestSeasonRepository;
import com.chris64233.cc.forestquota.repository.SeasonQuotaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class SeasonService {

    private final HarvestSeasonRepository seasonRepository;
    private final SeasonQuotaRepository quotaRepository;

    public SeasonService(HarvestSeasonRepository seasonRepository, SeasonQuotaRepository quotaRepository) {
        this.seasonRepository = seasonRepository;
        this.quotaRepository = quotaRepository;
    }

    @Transactional
    public HarvestSeason createSeason(String forestArea, LocalDate startDate, LocalDate endDate) {
        if (!StringUtils.hasText(forestArea)) {
            throw new BusinessValidationException("林区名称不能为空");
        }
        if (startDate == null || endDate == null) {
            throw new BusinessValidationException("许可季起止日期不能为空");
        }
        if (!endDate.isAfter(startDate)) {
            throw new BusinessValidationException("许可季结束日期必须晚于开始日期");
        }
        return seasonRepository.save(new HarvestSeason(forestArea.trim(), startDate, endDate));
    }

    @Transactional
    public QuotaLedgerEntry addQuota(Long seasonId, String species, BigDecimal authorizedVolume) {
        HarvestSeason season = seasonRepository.findById(seasonId)
                .orElseThrow(() -> new NotFoundException("许可季不存在: " + seasonId));
        if (!StringUtils.hasText(species)) {
            throw new BusinessValidationException("树种不能为空");
        }
        String normalizedSpecies = species.trim();
        BigDecimal volume = Volumes.requirePositive(authorizedVolume, "核准材积");
        if (quotaRepository.existsBySeasonIdAndSpecies(seasonId, normalizedSpecies)) {
            throw new ConflictException("该许可季树种额度已存在: " + normalizedSpecies);
        }
        SeasonQuota quota = new SeasonQuota(season, normalizedSpecies, volume);
        try {
            quotaRepository.saveAndFlush(quota);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("该许可季树种额度已存在: " + normalizedSpecies);
        }
        return toEntry(quota);
    }

    @Transactional(readOnly = true)
    public SeasonLedgerView getLedger(Long seasonId) {
        HarvestSeason season = seasonRepository.findById(seasonId)
                .orElseThrow(() -> new NotFoundException("许可季不存在: " + seasonId));
        List<QuotaLedgerEntry> quotas = quotaRepository.findBySeasonIdOrderBySpeciesAsc(seasonId).stream()
                .map(this::toEntry)
                .toList();
        return new SeasonLedgerView(season.getId(), season.getForestArea(),
                season.getStartDate(), season.getEndDate(), quotas);
    }

    private QuotaLedgerEntry toEntry(SeasonQuota quota) {
        return new QuotaLedgerEntry(quota.getSpecies(), quota.getAuthorizedVolume(),
                quota.getOccupiedVolume(), quota.getHarvestedVolume(), quota.available());
    }
}
