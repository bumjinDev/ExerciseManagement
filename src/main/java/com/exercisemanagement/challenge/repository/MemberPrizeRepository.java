package com.exercisemanagement.challenge.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.MemberPrize;

public interface MemberPrizeRepository extends JpaRepository<MemberPrize, String> {

    List<MemberPrize> findByTeamPrizeId(String teamPrizeId);
}
