package com.exercisemanagement.challenge.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.challenge.dto.request.ConfirmationRequest;
import com.exercisemanagement.challenge.dto.response.ConfirmationResponse;
import com.exercisemanagement.challenge.service.PeerConfirmationService;

import jakarta.validation.Valid;

/** 팀원 확인 (명세 6.4). 확인 시 그 제출의 볼륨이 개인·팀 누적에 반영된다. */
@RestController
public class ConfirmationController {

    private final PeerConfirmationService confirmationService;

    public ConfirmationController(PeerConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/api/submissions/{submissionId}/confirmations")
    public ResponseEntity<ConfirmationResponse> confirm(
            Authentication authentication,
            @PathVariable("submissionId") String submissionId,
            @Valid @RequestBody ConfirmationRequest request) {

        return ResponseEntity.ok(confirmationService.confirm(
                submissionId, authentication.getName(), request.getDecision()));
    }
}
