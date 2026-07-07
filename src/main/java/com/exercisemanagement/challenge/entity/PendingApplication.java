package com.exercisemanagement.challenge.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.exercisemanagement.challenge.common.CycleMode;

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

/**
 * 기준 측정 대기 중 참가 신청 보관 (명세 외 추가 테이블 — 변경사항 문서 2-3).
 * 최근 30일 기록이 없어 202로 응답한 신청의 개인 목표 입력을 보관하고,
 * 기준 측정 통과 시 PARTICIPATION 생성과 함께 삭제한다.
 */
@Entity
@Table(name = "pending_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingApplication {

    @Id
    @Column(name = "pending_id")
    private String pendingId;

    @Column(name = "challenge_id")
    private String challengeId;

    @Column(name = "participant_id")
    private String participantId;

    @Column(name = "intensity_coefficient")
    private BigDecimal intensityCoefficient;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_cycle_mode")
    private CycleMode goalCycleMode;

    @Column(name = "goal_cycle_interval")
    private int goalCycleInterval;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
