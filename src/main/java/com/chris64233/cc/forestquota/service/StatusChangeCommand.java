package com.chris64233.cc.forestquota.service;

import java.time.Instant;

public record StatusChangeCommand(String eventNo, String reason, Instant effectiveAt) {
}
