package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.Season;
import com.chris64233.cc.forestquota.domain.SeasonQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SeasonQuotaRepository extends JpaRepository<SeasonQuota, Long> {

    List<SeasonQuota> findBySeasonOrderBySpeciesAsc(Season season);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from SeasonQuota q where q.season = :season and q.species in :species order by q.species asc")
    List<SeasonQuota> lockBySeasonAndSpecies(@Param("season") Season season,
                                             @Param("species") Collection<String> species);
}
