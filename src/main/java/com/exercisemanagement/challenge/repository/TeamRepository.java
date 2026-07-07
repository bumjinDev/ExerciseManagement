package com.exercisemanagement.challenge.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.Team;

public interface TeamRepository extends JpaRepository<Team, String> {

    List<Team> findByChallengeId(String challengeId);
}
