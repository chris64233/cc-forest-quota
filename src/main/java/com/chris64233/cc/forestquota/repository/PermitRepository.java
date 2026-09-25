package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.Permit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PermitRepository extends JpaRepository<Permit, Long> {

    @EntityGraph(attributePaths = {"lines", "season"})
    Optional<Permit> findByApplicationNo(String applicationNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"lines", "season"})
    @Query("select p from Permit p where p.applicationNo = :applicationNo")
    Optional<Permit> lockByApplicationNo(@Param("applicationNo") String applicationNo);
}
