package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.Permit;
import com.chris64233.cc.forestquota.repository.PermitRepository;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.ApprovalRequest;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.PermitResponse;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.SettlementRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class PermitFacade {

    private final PermitService permitService;
    private final PermitRepository permitRepository;

    public PermitFacade(PermitService permitService, PermitRepository permitRepository) {
        this.permitService = permitService;
        this.permitRepository = permitRepository;
    }

    public PermitResponse approve(ApprovalRequest request) {
        try {
            return permitService.approve(request);
        } catch (DataIntegrityViolationException e) {
            Permit existing = permitRepository.findByApplicationNo(request.applicationNo())
                    .orElseThrow(() -> e);
            if (permitService.contentMatches(existing, request)) {
                return permitService.getPermit(request.applicationNo());
            }
            throw new ConflictException("申请号 " + request.applicationNo() + " 已存在且内容不一致");
        }
    }

    public PermitResponse settle(String applicationNo, SettlementRequest request) {
        return permitService.settle(applicationNo, request);
    }
}
