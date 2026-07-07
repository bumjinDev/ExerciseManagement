package com.exercisemanagement.challenge.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.TeamPrize;

public interface TeamPrizeRepository extends JpaRepository<TeamPrize, String> {

    List<TeamPrize> findBySettlementId(String settlementId);
}
