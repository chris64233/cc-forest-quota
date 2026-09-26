package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.AuditEvent;
import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.HarvestLicense;
import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseItem;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.domain.LicenseStatusEvent;
import com.chris64233.cc.forestquota.domain.SeasonQuota;
import com.chris64233.cc.forestquota.repository.AuditEventRepository;
import com.chris64233.cc.forestquota.repository.HarvestLicenseRepository;
import com.chris64233.cc.forestquota.repository.HarvestSeasonRepository;
import com.chris64233.cc.forestquota.repository.LicenseStatusEventRepository;
import com.chris64233.cc.forestquota.repository.SeasonQuotaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class LicenseService {

    private static final String APPLICATION_NO_UNIQUE_CONSTRAINT = "UK_LICENSE_APPLICATION_NO";

    private final HarvestLicenseRepository licenseRepository;
    private final HarvestSeasonRepository seasonRepository;
    private final SeasonQuotaRepository quotaRepository;
    private final AuditEventRepository auditEventRepository;
    private final LicenseStatusEventRepository statusEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTemplate;

    public LicenseService(HarvestLicenseRepository licenseRepository,
                          HarvestSeasonRepository seasonRepository,
                          SeasonQuotaRepository quotaRepository,
                          AuditEventRepository auditEventRepository,
                          LicenseStatusEventRepository statusEventRepository,
                          PlatformTransactionManager transactionManager) {
        this.licenseRepository = licenseRepository;
        this.seasonRepository = seasonRepository;
        this.quotaRepository = quotaRepository;
        this.auditEventRepository = auditEventRepository;
        this.statusEventRepository = statusEventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
    }

    public LicenseView approve(ApproveCommand command) {
        ApproveCommand normalized = normalize(command);
        try {
            return transactionTemplate.execute(status -> doApprove(normalized));
        } catch (DataIntegrityViolationException e) {
            if (isApplicationNoConflict(e)) {
                return resolveAfterConcurrentInsert(normalized);
            }
            throw e;
        }
    }

    public LicenseView settle(String applicationNo, List<SpeciesVolume> items) {
        List<SpeciesVolume> normalized = normalizeSettlement(items);
        String settlementHash = settlementHash(normalized);
        return transactionTemplate.execute(status -> doSettle(applicationNo, normalized, settlementHash));
    }

    public LicenseView suspend(String applicationNo, StatusChangeCommand command) {
        StatusChangeCommand normalized = normalizeStatusChange(command, true);
        return transactionTemplate.execute(status -> doSuspend(applicationNo, normalized));
    }

    public LicenseView resume(String applicationNo, StatusChangeCommand command) {
        StatusChangeCommand normalized = normalizeStatusChange(command, false);
        return transactionTemplate.execute(status -> doResume(applicationNo, normalized));
    }

    public LicenseView revoke(String applicationNo, StatusChangeCommand command) {
        StatusChangeCommand normalized = normalizeStatusChange(command, true);
        return transactionTemplate.execute(status -> doRevoke(applicationNo, normalized));
    }

    public List<StatusEventView> getTimeline(String applicationNo) {
        return readOnlyTemplate.execute(status -> {
            HarvestLicense license = licenseRepository.findByApplicationNo(applicationNo)
                    .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
            return statusEventRepository.findByLicenseIdOrderByEventNoAsc(license.getId()).stream()
                    .map(StatusEventView::of)
                    .toList();
        });
    }

    public List<SpeciesQuotaDetailView> getQuotaDetails(String applicationNo) {
        return readOnlyTemplate.execute(status -> {
            HarvestLicense license = licenseRepository.findByApplicationNo(applicationNo)
                    .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
            return license.getItems().stream()
                    .sorted(Comparator.comparing(LicenseItem::getSpecies))
                    .map(item -> new SpeciesQuotaDetailView(item.getSpecies(), item.getApprovedVolume(),
                            item.remainingVolume(),
                            item.getActualVolume() == null ? BigDecimal.ZERO.setScale(3) : item.getActualVolume(),
                            item.getReleasedVolume() == null ? BigDecimal.ZERO.setScale(3) : item.getReleasedVolume()))
                    .toList();
        });
    }

    public ResumeCheckView getResumeBlockers(String applicationNo) {
        return readOnlyTemplate.execute(status -> {
            HarvestLicense license = licenseRepository.findByApplicationNo(applicationNo)
                    .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
            List<String> blockers = computeResumeBlockers(license);
            return new ResumeCheckView(blockers.isEmpty(), blockers);
        });
    }

    public LicenseView getLicense(String applicationNo) {
        return readOnlyTemplate.execute(status -> licenseRepository.findByApplicationNo(applicationNo)
                .map(LicenseView::of)
                .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo)));
    }

    public List<AuditEventView> getAuditEvents(String applicationNo) {
        return readOnlyTemplate.execute(status -> {
            HarvestLicense license = licenseRepository.findByApplicationNo(applicationNo)
                    .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
            return auditEventRepository.findByLicenseIdOrderByIdAsc(license.getId()).stream()
                    .map(AuditEventView::of)
                    .toList();
        });
    }

    private LicenseView doApprove(ApproveCommand command) {
        return licenseRepository.findByApplicationNo(command.applicationNo())
                .map(existing -> {
                    if (!existing.getContentHash().equals(command.contentHash())) {
                        throw new ConflictException("申请号 " + command.applicationNo() + " 已存在且申请内容不一致");
                    }
                    return LicenseView.of(existing);
                })
                .orElseGet(() -> createLicense(command));
    }

    private LicenseView createLicense(ApproveCommand command) {
        HarvestSeason season = seasonRepository.findById(command.seasonId())
                .orElseThrow(() -> new NotFoundException("许可季不存在: " + command.seasonId()));
        if (command.workStartDate().isBefore(season.getStartDate())
                || command.workEndDate().isAfter(season.getEndDate())) {
            throw new BusinessValidationException("作业日期区间必须完全落在许可季 "
                    + season.getStartDate() + " 至 " + season.getEndDate() + " 内");
        }
        Map<String, BigDecimal> requested = new TreeMap<>();
        for (SpeciesVolume item : command.items()) {
            requested.put(item.species(), item.volume());
        }
        List<SeasonQuota> quotas = new ArrayList<>();
        List<String> shortages = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
            SeasonQuota quota = quotaRepository
                    .findBySeasonIdAndSpeciesForUpdate(season.getId(), entry.getKey())
                    .orElseThrow(() -> new BusinessValidationException("树种未核准额度: " + entry.getKey()));
            quotas.add(quota);
            if (quota.available().compareTo(entry.getValue()) < 0) {
                shortages.add(entry.getKey() + " 申请 " + entry.getValue().toPlainString()
                        + " 可用 " + quota.available().toPlainString());
            }
        }
        if (!shortages.isEmpty()) {
            throw new QuotaExceededException("树种额度不足，整笔驳回: " + String.join("; ", shortages));
        }
        quotas.forEach(quota -> quota.occupy(requested.get(quota.getSpecies())));

        HarvestLicense license = new HarvestLicense(command.applicationNo(), season,
                command.workStartDate(), command.workEndDate(), command.contentHash(), Instant.now());
        requested.forEach((species, volume) -> license.addItem(new LicenseItem(species, volume)));
        licenseRepository.save(license);
        auditEventRepository.save(new AuditEvent(license, season.getId(), AuditEventType.APPROVED, null,
                "批准申请，占用额度: " + describe(requested), Instant.now()));
        return LicenseView.of(license);
    }

    private LicenseView doSettle(String applicationNo, List<SpeciesVolume> items, String settlementHash) {
        HarvestLicense license = licenseRepository.findByApplicationNoForUpdate(applicationNo)
                .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
        if (license.getStatus() == LicenseStatus.SETTLED) {
            if (settlementHash.equals(license.getSettlementHash())) {
                return LicenseView.of(license);
            }
            throw new ConflictException("许可证已核销，申报内容与原核销记录不一致");
        }
        if (license.getStatus() == LicenseStatus.SUSPENDED) {
            throw new ConflictException("许可证已暂停，暂停期间不能申报实采");
        }
        if (license.getStatus() == LicenseStatus.REVOKED) {
            throw new ConflictException("许可证已撤销，不能申报实采");
        }
        Map<String, BigDecimal> actuals = new TreeMap<>();
        for (SpeciesVolume item : items) {
            actuals.put(item.species(), item.volume());
        }
        Map<String, LicenseItem> approvedBySpecies = new TreeMap<>();
        for (LicenseItem item : license.getItems()) {
            approvedBySpecies.put(item.getSpecies(), item);
        }
        if (!actuals.keySet().equals(approvedBySpecies.keySet())) {
            throw new BusinessValidationException("实采申报必须且只能覆盖全部批准树种: "
                    + approvedBySpecies.keySet());
        }
        for (Map.Entry<String, BigDecimal> entry : actuals.entrySet()) {
            BigDecimal approved = approvedBySpecies.get(entry.getKey()).getApprovedVolume();
            if (entry.getValue().compareTo(approved) > 0) {
                throw new BusinessValidationException("树种 " + entry.getKey() + " 实采量 "
                        + entry.getValue().toPlainString() + " 超过批准量 " + approved.toPlainString());
            }
        }
        Long seasonId = license.getSeason().getId();
        Instant now = Instant.now();
        for (Map.Entry<String, BigDecimal> entry : actuals.entrySet()) {
            String species = entry.getKey();
            BigDecimal actual = entry.getValue();
            LicenseItem item = approvedBySpecies.get(species);
            SeasonQuota quota = quotaRepository.findBySeasonIdAndSpeciesForUpdate(seasonId, species)
                    .orElseThrow(() -> new IllegalStateException("额度台账缺失: " + species));
            quota.settle(item.getApprovedVolume(), actual);
            item.settle(actual);
            if (item.getReleasedVolume().signum() > 0) {
                auditEventRepository.save(new AuditEvent(license, seasonId, AuditEventType.RELEASED, species,
                        "释放未使用占用: " + item.getReleasedVolume().toPlainString(), now));
            }
        }
        license.markSettled(settlementHash, now);
        auditEventRepository.save(new AuditEvent(license, seasonId, AuditEventType.SETTLED, null,
                "核销实采: " + describe(actuals), now));
        return LicenseView.of(license);
    }

    private LicenseView doSuspend(String applicationNo, StatusChangeCommand command) {
        HarvestLicense license = lockLicense(applicationNo);
        LicenseView idempotent = idempotentReplay(license, command, AuditEventType.SUSPENDED);
        if (idempotent != null) {
            return idempotent;
        }
        requireEventOrder(license, command);
        if (license.getStatus() != LicenseStatus.APPROVED) {
            throw new ConflictException("许可证当前状态为 " + license.getStatus() + "，不能暂停");
        }
        license.markSuspended();
        recordStatusEvent(license, command, AuditEventType.SUSPENDED);
        return LicenseView.of(license);
    }

    private LicenseView doResume(String applicationNo, StatusChangeCommand command) {
        HarvestLicense license = lockLicense(applicationNo);
        LicenseView idempotent = idempotentReplay(license, command, AuditEventType.RESUMED);
        if (idempotent != null) {
            return idempotent;
        }
        requireEventOrder(license, command);
        if (license.getStatus() != LicenseStatus.SUSPENDED) {
            throw new ConflictException("许可证当前状态为 " + license.getStatus() + "，不能恢复");
        }
        List<String> blockers = computeResumeBlockers(license);
        if (!blockers.isEmpty()) {
            throw new ConflictException("恢复检查未通过: " + String.join("; ", blockers));
        }
        license.markResumed();
        recordStatusEvent(license, command, AuditEventType.RESUMED);
        return LicenseView.of(license);
    }

    private LicenseView doRevoke(String applicationNo, StatusChangeCommand command) {
        HarvestLicense license = lockLicense(applicationNo);
        LicenseView idempotent = idempotentReplay(license, command, AuditEventType.REVOKED);
        if (idempotent != null) {
            return idempotent;
        }
        requireEventOrder(license, command);
        if (license.getStatus() == LicenseStatus.REVOKED) {
            throw new ConflictException("许可证已撤销，撤销是终态不能重复操作");
        }
        if (license.getStatus() == LicenseStatus.SETTLED) {
            throw new ConflictException("许可证已核销，不能撤销");
        }
        Long seasonId = license.getSeason().getId();
        Instant now = Instant.now();
        List<LicenseItem> items = license.getItems().stream()
                .sorted(Comparator.comparing(LicenseItem::getSpecies))
                .toList();
        for (LicenseItem item : items) {
            BigDecimal remaining = item.remainingVolume();
            if (remaining.signum() > 0) {
                SeasonQuota quota = quotaRepository.findBySeasonIdAndSpeciesForUpdate(seasonId, item.getSpecies())
                        .orElseThrow(() -> new IllegalStateException("额度台账缺失: " + item.getSpecies()));
                quota.release(remaining);
                item.releaseRemaining();
                auditEventRepository.save(new AuditEvent(license, seasonId, AuditEventType.RELEASED,
                        item.getSpecies(), "撤销释放未实采占用: " + remaining.toPlainString(), now));
            }
        }
        license.markRevoked();
        recordStatusEvent(license, command, AuditEventType.REVOKED);
        return LicenseView.of(license);
    }

    private HarvestLicense lockLicense(String applicationNo) {
        return licenseRepository.findByApplicationNoForUpdate(applicationNo)
                .orElseThrow(() -> new NotFoundException("许可证不存在: " + applicationNo));
    }

    private LicenseView idempotentReplay(HarvestLicense license, StatusChangeCommand command,
                                         AuditEventType expectedType) {
        return statusEventRepository.findByLicenseIdAndEventNo(license.getId(), command.eventNo())
                .map(existing -> {
                    if (existing.getEventType() != expectedType
                            || !Objects.equals(existing.getReason(), command.reason())) {
                        throw new ConflictException("事件号 " + command.eventNo() + " 已用于其他状态变更: "
                                + existing.getEventType());
                    }
                    return LicenseView.of(license);
                })
                .orElse(null);
    }

    private void requireEventOrder(HarvestLicense license, StatusChangeCommand command) {
        statusEventRepository.findTopByLicenseIdOrderByEventNoDesc(license.getId())
                .ifPresent(last -> {
                    if (command.eventNo() <= last.getEventNo()) {
                        throw new ConflictException("状态事件号必须递增以保持时间顺序，当前最大事件号: "
                                + last.getEventNo());
                    }
                });
    }

    private void recordStatusEvent(HarvestLicense license, StatusChangeCommand command, AuditEventType type) {
        Instant now = Instant.now();
        statusEventRepository.save(new LicenseStatusEvent(license, command.eventNo(), type,
                command.reason(), command.effectiveAt(), now));
        String details = switch (type) {
            case SUSPENDED -> "暂停许可证，原因: " + command.reason() + "，生效时间: " + command.effectiveAt();
            case RESUMED -> "恢复许可证" + (command.reason() == null ? "" : "，备注: " + command.reason());
            case REVOKED -> "撤销许可证，原因: " + command.reason() + "，生效时间: " + command.effectiveAt();
            default -> throw new IllegalArgumentException("非状态变更事件类型: " + type);
        };
        auditEventRepository.save(new AuditEvent(license, license.getSeason().getId(), type, null, details, now));
    }

    private List<String> computeResumeBlockers(HarvestLicense license) {
        List<String> blockers = new ArrayList<>();
        if (license.getStatus() != LicenseStatus.SUSPENDED) {
            blockers.add("许可证当前状态为 " + license.getStatus() + "，仅暂停状态可恢复");
        }
        LocalDate today = LocalDate.now();
        if (today.isAfter(license.getWorkEndDate())) {
            blockers.add("许可证作业期限已过: " + license.getWorkEndDate());
        }
        HarvestSeason season = license.getSeason();
        if (today.isAfter(season.getEndDate())) {
            blockers.add("许可季已结束: " + season.getEndDate());
        }
        for (LicenseItem item : license.getItems()) {
            SeasonQuota quota = quotaRepository
                    .findBySeasonIdAndSpecies(season.getId(), item.getSpecies())
                    .orElse(null);
            if (quota == null) {
                blockers.add("树种额度台账缺失: " + item.getSpecies());
            } else if (quota.getOccupiedVolume().compareTo(item.remainingVolume()) < 0) {
                blockers.add("树种 " + item.getSpecies() + " 占用额度异常: 台账占用 "
                        + quota.getOccupiedVolume().toPlainString() + " 低于许可证在占 "
                        + item.remainingVolume().toPlainString());
            }
        }
        return blockers;
    }

    private StatusChangeCommand normalizeStatusChange(StatusChangeCommand command, boolean reasonRequired) {
        if (command == null) {
            throw new BusinessValidationException("状态变更内容不能为空");
        }
        if (command.eventNo() == null || command.eventNo() <= 0) {
            throw new BusinessValidationException("事件号必须为正整数");
        }
        String reason = StringUtils.hasText(command.reason()) ? command.reason().trim() : null;
        if (reasonRequired && reason == null) {
            throw new BusinessValidationException("状态变更原因不能为空");
        }
        if (reason != null && reason.length() > 512) {
            throw new BusinessValidationException("状态变更原因最长 512 个字符");
        }
        Instant effectiveAt = command.effectiveAt() != null ? command.effectiveAt() : Instant.now();
        return new StatusChangeCommand(command.eventNo(), reason, effectiveAt);
    }

    private LicenseView resolveAfterConcurrentInsert(ApproveCommand command) {
        return readOnlyTemplate.execute(status -> {
            HarvestLicense license = licenseRepository.findByApplicationNo(command.applicationNo())
                    .orElseThrow(() -> new IllegalStateException("申请号冲突但无法读取已有记录"));
            if (!license.getContentHash().equals(command.contentHash())) {
                throw new ConflictException("申请号 " + command.applicationNo() + " 已存在且申请内容不一致");
            }
            return LicenseView.of(license);
        });
    }

    private boolean isApplicationNoConflict(DataIntegrityViolationException e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toUpperCase().contains(APPLICATION_NO_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ApproveCommand normalize(ApproveCommand command) {
        if (command == null) {
            throw new BusinessValidationException("申请内容不能为空");
        }
        if (!StringUtils.hasText(command.applicationNo())) {
            throw new BusinessValidationException("申请号不能为空");
        }
        if (command.seasonId() == null) {
            throw new BusinessValidationException("许可季不能为空");
        }
        if (command.workStartDate() == null || command.workEndDate() == null) {
            throw new BusinessValidationException("作业日期区间不能为空");
        }
        if (command.workEndDate().isBefore(command.workStartDate())) {
            throw new BusinessValidationException("作业结束日期不能早于开始日期");
        }
        List<SpeciesVolume> items = normalizeItems(command.items(), true);
        return new ApproveCommand(command.applicationNo().trim(), command.seasonId(),
                command.workStartDate(), command.workEndDate(), items);
    }

    private List<SpeciesVolume> normalizeSettlement(List<SpeciesVolume> items) {
        return normalizeItems(items, false);
    }

    private List<SpeciesVolume> normalizeItems(List<SpeciesVolume> items, boolean approve) {
        if (items == null || items.isEmpty()) {
            throw new BusinessValidationException("树种材积明细不能为空");
        }
        Set<String> seen = new HashSet<>();
        List<SpeciesVolume> normalized = new ArrayList<>();
        for (SpeciesVolume item : items) {
            if (item == null || !StringUtils.hasText(item.species())) {
                throw new BusinessValidationException("树种不能为空");
            }
            String species = item.species().trim();
            if (!seen.add(species)) {
                throw new BusinessValidationException("树种重复申报: " + species);
            }
            BigDecimal volume = approve
                    ? Volumes.requirePositive(item.volume(), "树种 " + species + " 申请材积")
                    : Volumes.requireNonNegative(item.volume(), "树种 " + species + " 实采材积");
            normalized.add(new SpeciesVolume(species, volume));
        }
        normalized.sort(Comparator.comparing(SpeciesVolume::species));
        return List.copyOf(normalized);
    }

    private String settlementHash(List<SpeciesVolume> items) {
        String content = items.stream()
                .sorted(Comparator.comparing(SpeciesVolume::species))
                .map(item -> item.species() + "=" + item.volume().toPlainString())
                .collect(Collectors.joining("|"));
        return Hashes.sha256(content);
    }

    private String describe(Map<String, BigDecimal> volumes) {
        return volumes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue().toPlainString())
                .collect(Collectors.joining(", "));
    }
}
