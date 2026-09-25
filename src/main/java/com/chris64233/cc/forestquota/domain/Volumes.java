package com.chris64233.cc.forestquota.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Volumes {

    public static final int SCALE = 3;

    private Volumes() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}
