package com.exercisemanagement.challenge.entity;

import java.time.LocalDateTime;

import com.exercisemanagement.challenge.common.ChallengeStatus;
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
 * 챌린지 (명세서 7.1.1). 컬럼명이 snake_case라 전 필드 @Column 명시.
 * creator_id는 명세 외 추가 컬럼(변경사항 문서 2-2).
 */
@Entity
@Table(name = "challenge")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Challenge {

    /** 소급 등록 유예(시간). 시스템 정책 고정값 (명세 9.3) */
    public static final long BACKDATE_GRACE_HOURS = 24L;

    @Id
    @Column(name = "challenge_id")
    private String challengeId;

    @Column(name = "category")
    private String category;

    @Column(name = "exercise")
    private String exercise;

    @Column(name = "team_capacity")
    private int teamCapacity;

    @Column(name = "team_count")
    private int teamCount;

    @Column(name = "prize_team_count")
    private int prizeTeamCount;

    @Column(name = "recruit_start")
    private LocalDateTime recruitStart;

    @Column(name = "recruit_end")
    private LocalDateTime recruitEnd;

    @Column(name = "perform_start")
    private LocalDateTime performStart;

    @Column(name = "perform_end")
    private LocalDateTime performEnd;

    /** 확인 윈도우 길이(초 단위 저장 — sql/03 헤더의 확정 사항) */
    @Column(name = "confirm_window_length")
    private long confirmWindowSeconds;

    @Column(name = "daily_cap")
    private int dailyCap;

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_mode")
    private CycleMode cycleMode;

    @Column(name = "cycle_interval")
    private int cycleInterval;

    @Column(name = "deposit_amount")
    private long depositAmount;

    @Column(name = "base_prize_pool")
    private long basePrizePool;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ChallengeStatus status;

    @Column(name = "creator_id")
    private String creatorId;

    /** 목표 참가 인원 = 팀 정원 × 팀 수 (F001) */
    public int targetParticipants() {
        return teamCapacity * teamCount;
    }

    /** 제출 완전 마감 = 수행 마감 + 소급 유예 24시간 (명세 9.3) */
    public LocalDateTime submissionFullDeadline() {
        return performEnd.plusHours(BACKDATE_GRACE_HOURS);
    }

    /** 확인 윈도우 종료 = 제출 완전 마감 + 확인 윈도우 길이 (명세 9.3) */
    public LocalDateTime confirmWindowEnd() {
        return submissionFullDeadline().plusSeconds(confirmWindowSeconds);
    }
}
