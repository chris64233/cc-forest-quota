package com.chris64233.cc.forestquota.service;

import com.chris64233.cc.forestquota.domain.LicenseStatus;

import java.util.List;

public record ResumeCheckView(String applicationNo, LicenseStatus status, boolean resumable,
                              List<String> blockers) {
}
