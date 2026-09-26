package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.SeasonQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeasonQuotaRepository extends JpaRepository<SeasonQuota, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from SeasonQuota q where q.season.id = :seasonId and q.species = :species")
    Optional<SeasonQuota> findBySeasonIdAndSpeciesForUpdate(@Param("seasonId") Long seasonId,
                                                            @Param("species") String species);

    List<SeasonQuota> findBySeasonIdOrderBySpeciesAsc(Long seasonId);

    Optional<SeasonQuota> findBySeasonIdAndSpecies(Long seasonId, String species);

    boolean existsBySeasonIdAndSpecies(Long seasonId, String species);
}
