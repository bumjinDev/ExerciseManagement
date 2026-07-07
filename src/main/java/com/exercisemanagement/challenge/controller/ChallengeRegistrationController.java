package com.exercisemanagement.challenge.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.challenge.dto.request.ChallengeCreateRequest;
import com.exercisemanagement.challenge.dto.response.ChallengeCreateResponse;
import com.exercisemanagement.challenge.service.ChallengeRegistrationService;

import jakarta.validation.Valid;

/** 챌린지 등록 (명세 6.1). 인증 필요 — 요청자가 등록자(creator_id)로 기록된다. */
@RestController
@RequestMapping("/api/challenges")
public class ChallengeRegistrationController {

    private final ChallengeRegistrationService registrationService;

    public ChallengeRegistrationController(ChallengeRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    public ResponseEntity<ChallengeCreateResponse> register(
            Authentication authentication,
            @Valid @RequestBody ChallengeCreateRequest request) {

        ChallengeCreateResponse response = registrationService.register(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
