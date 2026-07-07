package com.exercisemanagement.challenge.dto.response;

import com.exercisemanagement.challenge.common.ChallengeStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 챌린지 등록 응답 (명세서 7.2.3). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeCreateResponse {

    private String challengeId;
    private String category;
    private String exercise;
    private int targetParticipants;      // 팀 정원 × 팀 수
    private ChallengeStatus status;      // 등록 직후 "모집"
}
