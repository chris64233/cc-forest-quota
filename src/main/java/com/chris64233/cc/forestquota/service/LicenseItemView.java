package com.chris64233.cc.forestquota.service;

import java.math.BigDecimal;

public record LicenseItemView(String species, BigDecimal approvedVolume, BigDecimal actualVolume,
                              BigDecimal releasedVolume) {
}
