package com.exercisemanagement.challenge.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.exercisemanagement.challenge.dto.response.ErrorResponse;

/**
 * 챌린지 도메인 API 예외 핸들러.
 * 모든 실패 응답을 명세서 6.8 포맷 {"errorCode": "...", "message": "..."}으로 통일한다.
 */
@Order(0)
@RestControllerAdvice(basePackages = "com.exercisemanagement.challenge.controller")
public class GlobalChallengeExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalChallengeExceptionHandler.class);

    @ExceptionHandler(ChallengeApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ChallengeApiException ex) {
        logger.warn("도메인 예외: {} - {}", ex.getErrorCode().getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getStatus())
                .body(new ErrorResponse(ex.getErrorCode().getCode(), ex.getMessage()));
    }

    /**
     * @Valid 검증 실패.
     * 상금 팀 수 교차 검증(@AssertTrue prizeTeamCountValid)은 명세 코드 E-REG-PRIZE-COUNT로,
     * 그 외 필드 검증 실패는 신설 코드 E-REQ-INVALID로 응답한다(변경사항 문서 3-3).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String firstMessage = ex.getBindingResult().getAllErrors().isEmpty()
                ? ErrorCode.E_REQ_INVALID.getDefaultMessage()
                : ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();

        boolean prizeCountViolation = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(e -> "prizeTeamCountValid".equals(e.getField()));
        boolean dailyCapMissing = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(e -> "dailyCap".equals(e.getField()));

        ErrorCode code = prizeCountViolation ? ErrorCode.E_REG_PRIZE_COUNT
                : dailyCapMissing ? ErrorCode.E_REG_NO_DAILY_CAP
                : ErrorCode.E_REQ_INVALID;
        return ResponseEntity.status(code.getStatus())
                .body(new ErrorResponse(code.getCode(), firstMessage));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse(ErrorCode.E_REQ_INVALID.getCode(), "업로드 파일이 허용 크기(10MB)를 넘습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        logger.error("예상하지 못한 예외", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("E-SYS-INTERNAL", "서버 내부 오류가 발생했습니다."));
    }
}
