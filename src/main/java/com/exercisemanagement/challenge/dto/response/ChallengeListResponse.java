package com.exercisemanagement.challenge.dto.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 챌린지 목록 응답 (명세서 7.2.3). 빈 결과는 빈 목록. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChallengeListResponse {

    private List<ChallengeSummaryResponse> challenges;
}
