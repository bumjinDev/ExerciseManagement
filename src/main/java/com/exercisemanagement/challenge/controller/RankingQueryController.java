package com.exercisemanagement.challenge.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.challenge.dto.response.RankingStatusResponse;
import com.exercisemanagement.challenge.service.RankingService;

/**
 * 순위·현황 조회 (명세 6.6). 참가자 전용.
 * 화면 한 번의 갱신에 필요한 네 블록을 한 응답으로 반환한다 (폴링 대상).
 */
@RestController
public class RankingQueryController {

    private final RankingService rankingService;

    public RankingQueryController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping("/api/challenges/{challengeId}/rankings")
    public ResponseEntity<RankingStatusResponse> rankings(
            Authentication authentication,
            @PathVariable("challengeId") String challengeId) {

        return ResponseEntity.ok(rankingService.getRankings(challengeId, authentication.getName()));
    }
}
