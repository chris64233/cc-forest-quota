package com.chris64233.cc.forestquota.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Volumes {

    public static final int SCALE = 3;

    private Volumes() {
    }

    public static BigDecimal normalize(BigDecimal value, String field) {
        if (value == null) {
            throw new BusinessValidationException(field + "不能为空");
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new BusinessValidationException(field + "最多允许 " + SCALE + " 位小数");
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal requirePositive(BigDecimal value, String field) {
        BigDecimal normalized = normalize(value, field);
        if (normalized.signum() <= 0) {
            throw new BusinessValidationException(field + "必须为正数");
        }
        return normalized;
    }

    public static BigDecimal requireNonNegative(BigDecimal value, String field) {
        BigDecimal normalized = normalize(value, field);
        if (normalized.signum() < 0) {
            throw new BusinessValidationException(field + "不能为负数");
        }
        return normalized;
    }
}
