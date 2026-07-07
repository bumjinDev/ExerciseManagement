package com.exercisemanagement.challenge.exception;

/** 챌린지 도메인 비즈니스 예외. 에러 코드와 메시지를 담아 6.8 포맷으로 응답된다. */
public class ChallengeApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ChallengeApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public ChallengeApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
