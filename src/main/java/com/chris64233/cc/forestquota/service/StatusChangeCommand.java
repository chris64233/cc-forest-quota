package com.chris64233.cc.forestquota.service;

import java.time.Instant;

public record StatusChangeCommand(Long eventNo, String reason, Instant effectiveAt) {
}
