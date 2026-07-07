package com.exercisemanagement.challenge.dto.response;

import java.time.Duration;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.common.CycleMode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 챌린지 상세 응답 (명세서 7.2.3). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeDetailResponse {

    private String challengeId;
    private String category;
    private String exercise;
    private int teamCapacity;
    private int teamCount;
    private int prizeTeamCount;
    private long depositAmount;
    private long basePrizePool;
    private PeriodResponse recruitPeriod;
    private PeriodResponse performPeriod;
    private Duration confirmWindowLength;
    private int dailyCap;
    private CycleMode cycleMode;
    private int cycleInterval;
    private ChallengeStatus status;
    private int targetParticipants;
    private int currentParticipants;
}
