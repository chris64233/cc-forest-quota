package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.AuditEvent;
import com.chris64233.cc.forestquota.domain.Permit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByPermitOrderByOccurredAtAscIdAsc(Permit permit);
}
