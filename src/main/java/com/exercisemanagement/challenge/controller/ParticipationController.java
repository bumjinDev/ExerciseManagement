package com.exercisemanagement.challenge.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.exercisemanagement.challenge.dto.request.BaselineMeasurementRequest;
import com.exercisemanagement.challenge.dto.request.ParticipationApplyRequest;
import com.exercisemanagement.challenge.dto.response.BaselineRequiredResponse;
import com.exercisemanagement.challenge.dto.response.ParticipationCompleteResponse;
import com.exercisemanagement.challenge.service.ParticipationService;

import jakarta.validation.Valid;

/**
 * 참가 신청 (명세 6.2). 최근 30일 기록이 있으면 201로 완료,
 * 없으면 202로 기준 측정을 요청하고 기준 측정 제출(6.2.2)로 완료한다.
 */
@RestController
@RequestMapping("/api/challenges/{challengeId}/participations")
public class ParticipationController {

    private final ParticipationService participationService;

    public ParticipationController(ParticipationService participationService) {
        this.participationService = participationService;
    }

    @PostMapping
    public ResponseEntity<?> apply(
            Authentication authentication,
            @PathVariable("challengeId") String challengeId,
            @Valid @RequestBody ParticipationApplyRequest request) {

        ParticipationCompleteResponse response =
                participationService.apply(challengeId, authentication.getName(), request);

        if (response == null) {
            // 최근 30일 기록 없음 — 기준 측정 필요 (202 Accepted)
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new BaselineRequiredResponse("기준 측정 필요"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** 기준 측정 제출 (6.2.2). multipart: meta(JSON) + photo(이미지). */
    @PostMapping("/baseline-measurement")
    public ResponseEntity<ParticipationCompleteResponse> submitBaseline(
            Authentication authentication,
            @PathVariable("challengeId") String challengeId,
            @RequestPart("meta") @Valid BaselineMeasurementRequest meta,
            @RequestPart("photo") MultipartFile photo) {

        ParticipationCompleteResponse response =
                participationService.submitBaseline(challengeId, authentication.getName(), meta, photo);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
