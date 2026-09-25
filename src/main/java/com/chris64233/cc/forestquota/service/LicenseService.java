package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.AuditEvent;
import com.chris64233.cc.forestquota.domain.AuditEventType;
import com.chris64233.cc.forestquota.domain.HarvestLicense;
import com.chris64233.cc.forestquota.domain.HarvestSeason;
import com.chris64233.cc.forestquota.domain.LicenseItem;
import com.chris64233.cc.forestquota.domain.LicenseStatus;
import com.chris64233.cc.forestquota.domain.SeasonQuota;
import com.chris64233.cc.forestquota.repository.AuditEventRepository;
import com.chris64233.cc.forestquota.repository.HarvestLicenseRepository;
import com.chris64233.cc.forestquota.repository.HarvestSeasonRepository;
import com.chris64233.cc.forestquota.repository.SeasonQuotaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTemplate;

    public LicenseService(HarvestLicenseRepository licenseRepository,
                          HarvestSeasonRepository seasonRepository,
                          SeasonQuotaRepository quotaRepository,
                          AuditEventRepository auditEventRepository,
                          PlatformTransactionManager transactionManager) {
        this.licenseRepository = licenseRepository;
        this.seasonRepository = seasonRepository;
        this.quotaRepository = quotaRepository;
        this.auditEventRepository = auditEventRepository;
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
