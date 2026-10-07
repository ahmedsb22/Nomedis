package com.nomadix.traveler.repository;
import com.nomadix.traveler.entity.Traveler;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TravelerRepository extends JpaRepository<Traveler, Long> {}