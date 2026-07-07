package com.exercisemanagement.challenge.exception;

import org.springframework.http.HttpStatus;

/**
 * 챌린지 도메인 에러 코드 (명세서 6장·6.8).
 * 명세에 없는 상황의 신설 코드는 변경사항 문서 3-3에 기록되어 있다.
 */
public enum ErrorCode {

    // 등록 (6.1.3)
    E_REG_PRIZE_COUNT("E-REG-PRIZE-COUNT", HttpStatus.BAD_REQUEST, "상금 받을 팀 수는 팀 수보다 작아야 합니다."),
    E_REG_NO_DAILY_CAP("E-REG-NO-DAILY-CAP", HttpStatus.BAD_REQUEST, "하루 인증 횟수 상한은 필수입니다."),
    E_REG_UNKNOWN_EXERCISE("E-REG-UNKNOWN-EXERCISE", HttpStatus.BAD_REQUEST, "카테고리·종목이 정해진 목록에 없습니다."),

    // 참가 신청 (6.2.3)
    E_APP_NOT_RECRUITING("E-APP-NOT-RECRUITING", HttpStatus.CONFLICT, "모집 기간이 아닙니다."),
    E_APP_COEF_BELOW_ONE("E-APP-COEF-BELOW-ONE", HttpStatus.BAD_REQUEST, "강도 계수는 1 이상이어야 합니다."),
    E_APP_FREQ_OVER_CAP("E-APP-FREQ-OVER-CAP", HttpStatus.BAD_REQUEST, "목표 빈도가 하루 인증 횟수 상한을 넘습니다."),
    E_APP_INSUFFICIENT_BALANCE("E-APP-INSUFFICIENT-BALANCE", HttpStatus.PAYMENT_REQUIRED, "예치 잔액이 부족합니다."),
    E_APP_NO_PENDING_MEASUREMENT("E-APP-NO-PENDING-MEASUREMENT", HttpStatus.CONFLICT, "기준 측정을 요청받은 상태가 아닙니다."),
    E_APP_ALREADY_APPLIED("E-APP-ALREADY-APPLIED", HttpStatus.CONFLICT, "이미 이 챌린지에 신청했습니다."),        // 신설

    // 제출 (6.3.3, 8.2.1)
    E_SUB_DUP_HASH("E-SUB-DUP-HASH", HttpStatus.CONFLICT, "이미 사용된 인증 사진입니다."),
    E_SUB_META_MISMATCH("E-SUB-META-MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY, "인증 사진의 메타데이터가 정합하지 않습니다."),
    E_SUB_FREQ_VIOLATION("E-SUB-FREQ-VIOLATION", HttpStatus.TOO_MANY_REQUESTS, "하루 인증 횟수 상한 또는 인증 주기를 위반했습니다."),
    E_SUB_BACKDATE_EXCEEDED("E-SUB-BACKDATE-EXCEEDED", HttpStatus.BAD_REQUEST, "수행 날짜가 소급 유예(24시간)를 벗어났거나 미래 날짜입니다."),
    E_SUB_AFTER_DEADLINE("E-SUB-AFTER-DEADLINE", HttpStatus.CONFLICT, "제출 완전 마감 이후에는 제출할 수 없습니다."),
    E_SUB_BEFORE_START("E-SUB-BEFORE-START", HttpStatus.CONFLICT, "수행 시작 전에는 제출할 수 없습니다."),          // 신설
    E_SUB_DATE_OUT_OF_PERIOD("E-SUB-DATE-OUT-OF-PERIOD", HttpStatus.BAD_REQUEST, "수행 날짜가 수행 기간 밖입니다."), // 신설
    E_SUB_NOT_PARTICIPANT("E-SUB-NOT-PARTICIPANT", HttpStatus.FORBIDDEN, "이 챌린지의 참가자가 아닙니다."),
    E_SUB_NOT_FOUND("E-SUB-NOT-FOUND", HttpStatus.NOT_FOUND, "존재하지 않는 제출입니다."),                          // 신설

    // 팀원 확인 (6.4.3)
    E_CFM_SELF_CONFIRM("E-CFM-SELF-CONFIRM", HttpStatus.FORBIDDEN, "본인 제출은 확인할 수 없습니다."),
    E_CFM_NOT_TEAMMATE("E-CFM-NOT-TEAMMATE", HttpStatus.FORBIDDEN, "같은 팀의 팀원만 확인할 수 있습니다."),
    E_CFM_ALREADY_TERMINAL("E-CFM-ALREADY-TERMINAL", HttpStatus.CONFLICT, "이미 처리가 끝난 제출입니다."),
    E_CFM_WINDOW_CLOSED("E-CFM-WINDOW-CLOSED", HttpStatus.CONFLICT, "확인 윈도우가 종료되었습니다."),               // 신설

    // 챌린지 조회 (6.5.3)
    E_CHL_NOT_FOUND("E-CHL-NOT-FOUND", HttpStatus.NOT_FOUND, "존재하지 않는 챌린지입니다."),

    // 순위·현황 조회 (6.6.3)
    E_RNK_NOT_PARTICIPANT("E-RNK-NOT-PARTICIPANT", HttpStatus.FORBIDDEN, "이 챌린지의 참가자가 아닙니다."),
    E_RNK_NOT_STARTED("E-RNK-NOT-STARTED", HttpStatus.CONFLICT, "팀 편성 전이거나 무산된 챌린지입니다."),

    // 예치 (신설, 변경사항 문서 3-2·3-3)
    E_DEP_INVALID_AMOUNT("E-DEP-INVALID-AMOUNT", HttpStatus.BAD_REQUEST, "충전 금액은 0보다 큰 값이어야 합니다."),

    // 일반 요청 검증 실패 (신설)
    E_REQ_INVALID("E-REQ-INVALID", HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public String getDefaultMessage() { return defaultMessage; }
}
