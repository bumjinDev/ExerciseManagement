package com.exercisemanagement.challenge.dto.response;

import com.exercisemanagement.challenge.common.ChallengeStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 챌린지 목록 원소 (명세서 7.2.3). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeSummaryResponse {

    private String challengeId;
    private String category;
    private String exercise;
    private ChallengeStatus status;
    private int targetParticipants;
    private int currentParticipants;
    private PeriodResponse recruitPeriod;
    private PeriodResponse performPeriod;
}
