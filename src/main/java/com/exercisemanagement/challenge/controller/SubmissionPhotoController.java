package com.exercisemanagement.challenge.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.challenge.service.SubmissionQueryService;

/**
 * 인증샷 다운로드 (신설 — 변경사항 문서 3-5).
 * 팀원 확인 화면이 <img> 태그로 이 경로를 참조한다. 같은 챌린지 참가자만 접근 가능.
 */
@RestController
public class SubmissionPhotoController {

    private final SubmissionQueryService queryService;

    public SubmissionPhotoController(SubmissionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/submissions/{submissionId}/photo")
    public ResponseEntity<byte[]> photo(
            Authentication authentication,
            @PathVariable("submissionId") String submissionId) {

        byte[] bytes = queryService.photo(submissionId, authentication.getName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }
}
