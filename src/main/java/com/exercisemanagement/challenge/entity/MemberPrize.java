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

/** 팀원 분배 기록 (명세서 7.1.10). 나머지 환수 행은 participant_id NULL. */
@Entity
@Table(name = "member_prize")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberPrize {

    @Id
    @Column(name = "member_prize_id")
    private String memberPrizeId;

    @Column(name = "team_prize_id")
    private String teamPrizeId;

    @Column(name = "participant_id")
    private String participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type")
    private PrizeEntryType entryType;

    @Column(name = "amount")
    private long amount;
}
