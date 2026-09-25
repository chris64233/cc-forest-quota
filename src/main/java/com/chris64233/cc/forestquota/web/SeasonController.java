package com.chris64233.cc.forestquota.web;

import com.chris64233.cc.forestquota.service.SeasonService;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.SeasonCreateRequest;
import com.chris64233.cc.forestquota.web.dto.SeasonDtos.SeasonLedgerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seasons")
public class SeasonController {

    private final SeasonService seasonService;

    public SeasonController(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonLedgerResponse create(@Valid @RequestBody SeasonCreateRequest request) {
        return seasonService.createSeason(request);
    }

    @GetMapping("/{forestArea}/{seasonCode}/ledger")
    public SeasonLedgerResponse ledger(@PathVariable String forestArea,
                                       @PathVariable String seasonCode) {
        return seasonService.getLedger(forestArea, seasonCode);
    }
}
