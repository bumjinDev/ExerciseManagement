package com.exercisemanagement.challenge.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.exercisemanagement.challenge.dto.request.SubmissionCreateRequest;
import com.exercisemanagement.challenge.dto.response.MySubmissionsResponse;
import com.exercisemanagement.challenge.dto.response.PendingSubmissionsResponse;
import com.exercisemanagement.challenge.dto.response.SubmissionCreateResponse;
import com.exercisemanagement.challenge.service.SubmissionQueryService;
import com.exercisemanagement.challenge.service.SubmissionValidationService;

import jakarta.validation.Valid;

/**
 * 인증 제출 (명세 6.3)과 제출 조회.
 * - POST: 기계 검증 통과 시 확인 대기로 접수 (202)
 * - GET /me: 내 제출 현황 (6.7)
 * - GET /pending: 확인 대기 목록 (신설 — 변경사항 문서 3-1)
 */
@RestController
@RequestMapping("/api/challenges/{challengeId}/submissions")
public class SubmissionController {

    private final SubmissionValidationService validationService;
    private final SubmissionQueryService queryService;

    public SubmissionController(SubmissionValidationService validationService,
                                SubmissionQueryService queryService) {
        this.validationService = validationService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<SubmissionCreateResponse> submit(
            Authentication authentication,
            @PathVariable("challengeId") String challengeId,
            @RequestPart("meta") @Valid SubmissionCreateRequest meta,
            @RequestPart("photo") MultipartFile photo) {

        SubmissionCreateResponse response =
                validationService.submit(challengeId, authentication.getName(), meta, photo);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<MySubmissionsResponse> mySubmissions(
            Authentication authentication,
            @PathVariable("challengeId") String challengeId,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(queryService.mySubmissions(challengeId, authentication.getName(), date));
    }

    @GetMapping("/pending")
    public ResponseEntity<PendingSubmissionsResponse> pendingSubmissions(
            Authentication authentication,
            @PathVariable("challengeId") String challengeId) {

        return ResponseEntity.ok(queryService.pendingSubmissions(challengeId, authentication.getName()));
    }
}
