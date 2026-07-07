package com.exercisemanagement.challenge.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.challenge.common.ChallengeStatus;
import com.exercisemanagement.challenge.dto.response.ChallengeDetailResponse;
import com.exercisemanagement.challenge.dto.response.ChallengeListResponse;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;
import com.exercisemanagement.challenge.service.ChallengeQueryService;

/** 챌린지 목록·상세 조회 (명세 6.5). 공개 읽기 — 인증 불필요. */
@RestController
@RequestMapping("/api/challenges")
public class ChallengeQueryController {

    private final ChallengeQueryService queryService;

    public ChallengeQueryController(ChallengeQueryService queryService) {
        this.queryService = queryService;
    }

    /** 목록 조회. status는 한글 상태값(모집/시작/종료/무산)으로 거른다. 미지정 시 전체. */
    @GetMapping
    public ResponseEntity<ChallengeListResponse> list(
            @RequestParam(name = "status", required = false) String status) {

        ChallengeStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = ChallengeStatus.from(status);
            } catch (IllegalArgumentException e) {
                throw new ChallengeApiException(ErrorCode.E_REQ_INVALID, "알 수 없는 챌린지 상태: " + status);
            }
        }
        return ResponseEntity.ok(queryService.list(filter));
    }

    @GetMapping("/{challengeId}")
    public ResponseEntity<ChallengeDetailResponse> detail(@PathVariable("challengeId") String challengeId) {
        return ResponseEntity.ok(queryService.detail(challengeId));
    }
}
