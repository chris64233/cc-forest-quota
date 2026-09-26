package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.LicenseStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LicenseStatusEventRepository extends JpaRepository<LicenseStatusEvent, Long> {

    Optional<LicenseStatusEvent> findByLicenseIdAndEventNo(Long licenseId, String eventNo);

    Optional<LicenseStatusEvent> findFirstByLicenseIdOrderByEffectiveAtDescIdDesc(Long licenseId);

    List<LicenseStatusEvent> findByLicenseIdOrderByEffectiveAtAscIdAsc(Long licenseId);
}
