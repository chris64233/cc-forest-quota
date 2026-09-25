package com.chris64233.cc.forestquota.web;

import com.chris64233.cc.forestquota.service.PermitFacade;
import com.chris64233.cc.forestquota.service.PermitService;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.ApprovalRequest;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.AuditEventView;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.PermitResponse;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.SettlementRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/permits")
public class PermitController {

    private final PermitFacade permitFacade;
    private final PermitService permitService;

    public PermitController(PermitFacade permitFacade, PermitService permitService) {
        this.permitFacade = permitFacade;
        this.permitService = permitService;
    }

    @PostMapping("/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public PermitResponse approve(@Valid @RequestBody ApprovalRequest request) {
        return permitFacade.approve(request);
    }

    @PostMapping("/{applicationNo}/settlement")
    public PermitResponse settle(@PathVariable String applicationNo,
                                 @Valid @RequestBody SettlementRequest request) {
        return permitFacade.settle(applicationNo, request);
    }

    @GetMapping("/{applicationNo}")
    public PermitResponse detail(@PathVariable String applicationNo) {
        return permitService.getPermit(applicationNo);
    }

    @GetMapping("/{applicationNo}/events")
    public List<AuditEventView> events(@PathVariable String applicationNo) {
        return permitService.getEvents(applicationNo);
    }
}
