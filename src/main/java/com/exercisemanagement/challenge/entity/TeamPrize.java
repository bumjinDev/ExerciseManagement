package com.exercisemanagement.challenge.entity;

import com.exercisemanagement.challenge.common.PrizeEntryType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 팀 몫 분배 기록 (명세서 7.1.9). 시스템 환수 행은 team_id NULL. */
@Entity
@Table(name = "team_prize")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamPrize {

    @Id
    @Column(name = "team_prize_id")
    private String teamPrizeId;

    @Column(name = "settlement_id")
    private String settlementId;

    @Column(name = "team_id")
    private String teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type")
    private PrizeEntryType entryType;

    @Column(name = "amount")
    private long amount;
}
