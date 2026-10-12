package com.nomadix.traveler.profiling;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TravelerProfileRepository extends JpaRepository<TravelerProfile, String> {

    List<TravelerProfile> findAllByOrderByScoreDesc();

    List<TravelerProfile> findByScoreLessThanOrderByScoreAsc(int score);

    long countBySegment(String segment);
}