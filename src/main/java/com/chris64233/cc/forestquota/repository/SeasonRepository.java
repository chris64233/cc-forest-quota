package com.chris64233.cc.forestquota.repository;

import com.chris64233.cc.forestquota.domain.Season;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeasonRepository extends JpaRepository<Season, Long> {

    Optional<Season> findByForestAreaAndSeasonCode(String forestArea, String seasonCode);
}
