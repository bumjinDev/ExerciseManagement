package com.exercisemanagement.challenge.entity;

import java.math.BigDecimal;

import com.exercisemanagement.challenge.common.CycleMode;
import com.exercisemanagement.challenge.common.ParticipationStatus;

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

/** 참가 (명세서 7.1.3). */
@Entity
@Table(name = "participation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Participation {

    @Id
    @Column(name = "participation_id")
    private String participationId;

    @Column(name = "challenge_id")
    private String challengeId;

    @Column(name = "participant_id")
    private String participantId;

    @Column(name = "formation_skill")
    private BigDecimal formationSkill;

    @Column(name = "goal_baseline")
    private BigDecimal goalBaseline;

    @Column(name = "intensity_coefficient")
    private BigDecimal intensityCoefficient;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_cycle_mode")
    private CycleMode goalCycleMode;

    @Column(name = "goal_cycle_interval")
    private int goalCycleInterval;

    @Column(name = "deposit_amount")
    private long depositAmount;

    @Column(name = "team_id")
    private String teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ParticipationStatus status;

    /** 개인 강도 하한 = 개인 목표 기준값 × 강도 계수 (명세 8.4.1) */
    public BigDecimal intensityFloor() {
        return goalBaseline.multiply(intensityCoefficient);
    }
}
