package com.chris64233.cc.forestquota.service;

import java.util.List;

public record ResumeCheckView(boolean resumable, List<String> reasons) {
}
