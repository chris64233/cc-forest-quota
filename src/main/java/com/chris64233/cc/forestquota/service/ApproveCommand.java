package com.chris64233.cc.forestquota.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record ApproveCommand(String applicationNo, Long seasonId, LocalDate workStartDate,
                             LocalDate workEndDate, List<SpeciesVolume> items) {

    public String contentHash() {
        StringBuilder content = new StringBuilder()
                .append(seasonId).append('|')
                .append(workStartDate).append('|')
                .append(workEndDate);
        items.stream()
                .sorted(Comparator.comparing(SpeciesVolume::species))
                .forEach(item -> content.append('|')
                        .append(item.species()).append('=')
                        .append(item.volume().toPlainString()));
        return Hashes.sha256(content.toString());
    }
}
