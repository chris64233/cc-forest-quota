package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.HarvestLicense;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HarvestLicenseRepository extends JpaRepository<HarvestLicense, Long> {

    Optional<HarvestLicense> findByApplicationNo(String applicationNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from HarvestLicense l where l.applicationNo = :applicationNo")
    Optional<HarvestLicense> findByApplicationNoForUpdate(@Param("applicationNo") String applicationNo);
}
