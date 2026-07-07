package com.exercisemanagement.challenge.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.challenge.dto.request.DepositChargeRequest;
import com.exercisemanagement.challenge.dto.response.DepositChargeResponse;
import com.exercisemanagement.challenge.dto.response.DepositStatusResponse;
import com.exercisemanagement.challenge.service.DepositService;

import jakarta.validation.Valid;

/** 예치 충전·현황 (F010, 신설 API — 변경사항 문서 3-2). 모의 충전. */
@RestController
@RequestMapping("/api/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping
    public ResponseEntity<DepositChargeResponse> charge(
            Authentication authentication,
            @Valid @RequestBody DepositChargeRequest request) {

        long balance = depositService.charge(authentication.getName(), request.getAmount());
        return ResponseEntity.ok(new DepositChargeResponse(balance));
    }

    @GetMapping("/me")
    public ResponseEntity<DepositStatusResponse> myDeposits(Authentication authentication) {
        return ResponseEntity.ok(depositService.getStatus(authentication.getName()));
    }
}
