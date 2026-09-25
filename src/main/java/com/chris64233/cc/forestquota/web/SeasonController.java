package com.chris64233.cc.forestquota.web;

import com.chris64233.cc.forestquota.service.QuotaLedgerEntry;
import com.chris64233.cc.forestquota.service.SeasonLedgerView;
import com.chris64233.cc.forestquota.service.SeasonService;
import com.chris64233.cc.forestquota.web.dto.CreateQuotaRequest;
import com.chris64233.cc.forestquota.web.dto.CreateSeasonRequest;
import com.chris64233.cc.forestquota.web.dto.SeasonResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seasons")
public class SeasonController {

    private final SeasonService seasonService;

    public SeasonController(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    @PostMapping
    public ResponseEntity<SeasonResponse> createSeason(@Valid @RequestBody CreateSeasonRequest request) {
        SeasonResponse response = SeasonResponse.of(
                seasonService.createSeason(request.forestArea(), request.startDate(), request.endDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{seasonId}/quotas")
    public ResponseEntity<QuotaLedgerEntry> addQuota(@PathVariable Long seasonId,
                                                     @Valid @RequestBody CreateQuotaRequest request) {
        QuotaLedgerEntry entry = seasonService.addQuota(seasonId, request.species(), request.authorizedVolume());
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @GetMapping("/{seasonId}/ledger")
    public SeasonLedgerView getLedger(@PathVariable Long seasonId) {
        return seasonService.getLedger(seasonId);
    }
}
