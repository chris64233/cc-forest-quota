package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.AuditEvent;
import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.Permit;
import com.chris64233.cc.forestquota.domain.PermitLine;
import com.chris64233.cc.forestquota.domain.PermitStatus;
import com.chris64233.cc.forestquota.domain.Season;
import com.chris64233.cc.forestquota.domain.SeasonQuota;
import com.chris64233.cc.forestquota.domain.Volumes;
import com.chris64233.cc.forestquota.repository.AuditEventRepository;
import com.chris64233.cc.forestquota.repository.PermitRepository;
import com.chris64233.cc.forestquota.repository.SeasonQuotaRepository;
import com.chris64233.cc.forestquota.repository.SeasonRepository;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.ApprovalRequest;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.AuditEventView;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.PermitLineView;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.PermitResponse;
import com.chris64233.cc.forestquota.web.dto.PermitDtos.SettlementRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PermitService {

    private final SeasonRepository seasonRepository;
    private final SeasonQuotaRepository quotaRepository;
    private final PermitRepository permitRepository;
    private final AuditEventRepository auditEventRepository;

    public PermitService(SeasonRepository seasonRepository,
                         SeasonQuotaRepository quotaRepository,
                         PermitRepository permitRepository,
                         AuditEventRepository auditEventRepository) {
        this.seasonRepository = seasonRepository;
        this.quotaRepository = quotaRepository;
        this.permitRepository = permitRepository;
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public PermitResponse approve(ApprovalRequest request) {
        var existing = permitRepository.findByApplicationNo(request.applicationNo());
        if (existing.isPresent()) {
            Permit permit = existing.get();
            if (contentMatches(permit, request)) {
                return toResponse(permit);
            }
            throw new ConflictException("申请号 " + request.applicationNo() + " 已存在且内容不一致");
        }

        if (request.workEndDate().isBefore(request.workStartDate())) {
            throw new BusinessException("作业结束日期不能早于开始日期");
        }
        Season season = seasonRepository
                .findByForestAreaAndSeasonCode(request.forestArea(), request.seasonCode())
                .orElseThrow(() -> new NotFoundException(
                        "许可季不存在: " + request.forestArea() + "/" + request.seasonCode()));
        if (!season.covers(request.workStartDate(), request.workEndDate())) {
            throw new BusinessException("作业日期区间必须完全落在许可季内");
        }

        Map<String, BigDecimal> requested = requestedVolumes(request);
        Map<String, SeasonQuota> quotas = lockQuotas(season, requested);

        List<String> insufficient = new ArrayList<>();
        for (var entry : requested.entrySet()) {
            SeasonQuota quota = quotas.get(entry.getKey());
            if (quota == null) {
                throw new BusinessException("许可季未配置树种额度: " + entry.getKey());
            }
            if (quota.getAvailableVolume().compareTo(entry.getValue()) < 0) {
                insufficient.add(entry.getKey());
            }
        }
        if (!insufficient.isEmpty()) {
            throw new BusinessException("树种可用额度不足，整笔申请驳回: " + String.join(", ", insufficient));
        }

        Permit permit = new Permit(request.applicationNo(), season,
                request.workStartDate(), request.workEndDate());
        for (var entry : requested.entrySet()) {
            quotas.get(entry.getKey()).reserve(entry.getValue());
            permit.addLine(new PermitLine(permit, entry.getKey(), entry.getValue()));
        }
        permitRepository.save(permit);
        auditEventRepository.save(new AuditEvent(permit, AuditEventType.APPROVED,
                "批准申请，占用额度: " + describe(requested)));
        return toResponse(permit);
    }

    @Transactional
    public PermitResponse settle(String applicationNo, SettlementRequest request) {
        Permit permit = permitRepository.lockByApplicationNo(applicationNo)
                .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));

        Map<String, BigDecimal> declared = new TreeMap<>();
        for (var line : request.lines()) {
            if (line.actualVolume() == null || line.actualVolume().signum() < 0) {
                throw new BusinessException("实采量不得为负: " + line.species());
            }
            BigDecimal previous = declared.put(line.species(), Volumes.normalize(line.actualVolume()));
            if (previous != null) {
                throw new BusinessException("实采申报中树种重复: " + line.species());
            }
        }

        if (permit.getStatus() == PermitStatus.SETTLED) {
            Map<String, BigDecimal> settled = permit.getLines().stream()
                    .collect(Collectors.toMap(PermitLine::getSpecies, PermitLine::getHarvestedVolume));
            if (sameContent(settled, declared)) {
                return toResponse(permit);
            }
            throw new ConflictException("申请号 " + applicationNo + " 已核销，申报内容不一致");
        }

        Map<String, PermitLine> lines = permit.getLines().stream()
                .collect(Collectors.toMap(PermitLine::getSpecies, Function.identity()));
        if (!declared.keySet().equals(lines.keySet())) {
            throw new BusinessException("实采申报必须覆盖许可证全部树种，且不得包含未批准树种");
        }
        for (var entry : declared.entrySet()) {
            BigDecimal approved = lines.get(entry.getKey()).getApprovedVolume();
            if (entry.getValue().compareTo(approved) > 0) {
                throw new BusinessException("树种 " + entry.getKey() + " 实采量超过批准量");
            }
        }

        Season season = permit.getSeason();
        Map<String, SeasonQuota> quotas = lockQuotas(season, declared);

        Map<String, BigDecimal> released = new TreeMap<>();
        for (var entry : declared.entrySet()) {
            PermitLine line = lines.get(entry.getKey());
            SeasonQuota quota = quotas.get(entry.getKey());
            quota.settle(line.getApprovedVolume(), entry.getValue());
            line.settle(entry.getValue());
            BigDecimal unused = line.getApprovedVolume().subtract(entry.getValue());
            if (unused.signum() > 0) {
                released.put(entry.getKey(), unused);
            }
        }
        permit.markSettled();
        auditEventRepository.save(new AuditEvent(permit, AuditEventType.SETTLED,
                "核销许可证，实采量: " + describe(declared)));
        if (!released.isEmpty()) {
            auditEventRepository.save(new AuditEvent(permit, AuditEventType.RELEASED,
                    "释放未使用占用量: " + describe(released)));
        }
        return toResponse(permit);
    }

    @Transactional(readOnly = true)
    public PermitResponse getPermit(String applicationNo) {
        return toResponse(permitRepository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo)));
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> getEvents(String applicationNo) {
        Permit permit = permitRepository.findByApplicationNo(applicationNo)
                .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
        return auditEventRepository.findByPermitOrderByOccurredAtAscIdAsc(permit).stream()
                .map(event -> new AuditEventView(event.getEventType(), event.getDetail(), event.getOccurredAt()))
                .toList();
    }

    private Map<String, BigDecimal> requestedVolumes(ApprovalRequest request) {
        Map<String, BigDecimal> requested = new TreeMap<>();
        for (var line : request.lines()) {
            if (line.volume() == null || line.volume().signum() <= 0) {
                throw new BusinessException("申请材积必须为正: " + line.species());
            }
            BigDecimal previous = requested.put(line.species(), Volumes.normalize(line.volume()));
            if (previous != null) {
                throw new BusinessException("申请中树种重复: " + line.species());
            }
        }
        return requested;
    }

    private Map<String, SeasonQuota> lockQuotas(Season season, Map<String, BigDecimal> bySpecies) {
        return quotaRepository.lockBySeasonAndSpecies(season, bySpecies.keySet()).stream()
                .collect(Collectors.toMap(SeasonQuota::getSpecies, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    boolean contentMatches(Permit existing, ApprovalRequest request) {
        Season season = existing.getSeason();
        boolean sameHeader = season.getForestArea().equals(request.forestArea())
                && season.getSeasonCode().equals(request.seasonCode())
                && existing.getWorkStartDate().equals(request.workStartDate())
                && existing.getWorkEndDate().equals(request.workEndDate());
        Map<String, BigDecimal> existingLines = existing.getLines().stream()
                .collect(Collectors.toMap(PermitLine::getSpecies, PermitLine::getApprovedVolume));
        Map<String, BigDecimal> requested = new TreeMap<>();
        for (var line : request.lines()) {
            requested.merge(line.species(), Volumes.normalize(line.volume()), BigDecimal::add);
        }
        return sameHeader && sameContent(existingLines, requested);
    }

    private boolean sameContent(Map<String, BigDecimal> left, Map<String, BigDecimal> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        return left.entrySet().stream()
                .allMatch(entry -> Volumes.same(entry.getValue(), right.get(entry.getKey())));
    }

    private String describe(Map<String, BigDecimal> volumes) {
        return volumes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().toPlainString())
                .collect(Collectors.joining(", "));
    }

    private PermitResponse toResponse(Permit permit) {
        Season season = permit.getSeason();
        List<PermitLineView> lines = permit.getLines().stream()
                .map(line -> new PermitLineView(
                        line.getSpecies(),
                        line.getApprovedVolume(),
                        line.getHarvestedVolume(),
                        line.getReleasedVolume()))
                .toList();
        return new PermitResponse(
                permit.getApplicationNo(),
                season.getForestArea(),
                season.getSeasonCode(),
                permit.getWorkStartDate(),
                permit.getWorkEndDate(),
                permit.getStatus(),
                lines);
    }

}
