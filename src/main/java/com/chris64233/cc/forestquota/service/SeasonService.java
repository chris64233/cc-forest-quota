package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.Season;
import com.chris64233.cc.forestquota.domain.SeasonQuota;
import com.chris64233.cc.forestquota.repository.SeasonQuotaRepository;
import com.chris64233.cc.forestquota.repository.SeasonRepository;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.SeasonCreateRequest;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.SeasonLedgerResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SeasonService {

    private final SeasonRepository seasonRepository;
    private final SeasonQuotaRepository quotaRepository;

    public SeasonService(SeasonRepository seasonRepository, SeasonQuotaRepository quotaRepository) {
        this.seasonRepository = seasonRepository;
        this.quotaRepository = quotaRepository;
    }

    @Transactional
    public SeasonLedgerResponse createSeason(SeasonCreateRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessException("许可季结束日期不能早于开始日期");
        }
        Set<String> seen = new HashSet<>();
        for (var quota : request.quotas()) {
            if (!seen.add(quota.species())) {
                throw new BusinessException("同一许可季内树种重复: " + quota.species());
            }
        }
        seasonRepository.findByForestAreaAndSeasonCode(request.forestArea(), request.seasonCode())
                .ifPresent(existing -> {
                    throw new ConflictException("许可季已存在: " + request.forestArea() + "/" + request.seasonCode());
                });
        Season season = seasonRepository.save(new Season(
                request.forestArea(), request.seasonCode(), request.startDate(), request.endDate()));
        List<SeasonQuota> quotas = request.quotas().stream()
                .map(spec -> new SeasonQuota(season, spec.species(), spec.authorizedVolume()))
                .toList();
        quotaRepository.saveAll(quotas);
        return toLedger(season, quotas);
    }

    @Transactional(readOnly = true)
    public SeasonLedgerResponse getLedger(String forestArea, String seasonCode) {
        Season season = seasonRepository.findByForestAreaAndSeasonCode(forestArea, seasonCode)
                .orElseThrow(() -> new NotFoundException("许可季不存在: " + forestArea + "/" + seasonCode));
        return toLedger(season, quotaRepository.findBySeasonOrderBySpeciesAsc(season));
    }

    private SeasonLedgerResponse toLedger(Season season, List<SeasonQuota> quotas) {
        List<QuotaLedgerEntry> entries = quotas.stream()
                .map(q -> new QuotaLedgerEntry(
                        q.getSpecies(),
                        q.getAuthorizedVolume(),
                        q.getReservedVolume(),
                        q.getHarvestedVolume(),
                        q.getAvailableVolume()))
                .toList();
        return new SeasonLedgerResponse(
                season.getForestArea(), season.getSeasonCode(),
                season.getStartDate(), season.getEndDate(), entries);
    }
}
