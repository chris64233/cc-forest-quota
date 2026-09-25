package com.chris64233.cc.forestquota.web;

import com.chris64233.cc.forestquota.service.ApproveCommand;
import com.chris64233.cc.forestquota.service.AuditEventView;
import com.chris64233.cc.forestquota.service.LicenseService;
import com.chris64233.cc.forestquota.service.LicenseView;
import com.chris64233.cc.forestquota.service.SpeciesVolume;
import com.chris64233.cc.forestquota.web.dto.ApproveLicenseRequest;
import com.chris64233.cc.forestquota.web.dto.SettleLicenseRequest;
import com.chris64233.cc.forestquota.web.dto.SpeciesVolumeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/licenses")
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @PostMapping("/approvals")
    public LicenseView approve(@Valid @RequestBody ApproveLicenseRequest request) {
        return licenseService.approve(new ApproveCommand(request.applicationNo(), request.seasonId(),
                request.workStartDate(), request.workEndDate(), toItems(request.items())));
    }

    @PostMapping("/{applicationNo}/settlement")
    public LicenseView settle(@PathVariable String applicationNo,
                              @Valid @RequestBody SettleLicenseRequest request) {
        return licenseService.settle(applicationNo, toItems(request.items()));
    }

    @GetMapping("/{applicationNo}")
    public LicenseView getLicense(@PathVariable String applicationNo) {
        return licenseService.getLicense(applicationNo);
    }

    @GetMapping("/{applicationNo}/audit-events")
    public List<AuditEventView> getAuditEvents(@PathVariable String applicationNo) {
        return licenseService.getAuditEvents(applicationNo);
    }

    private static List<SpeciesVolume> toItems(List<SpeciesVolumeRequest> items) {
        return items.stream()
                .map(item -> new SpeciesVolume(item.species(), item.volume()))
                .toList();
    }
}
