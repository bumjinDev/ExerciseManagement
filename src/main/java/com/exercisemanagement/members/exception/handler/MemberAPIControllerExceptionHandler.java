package com.exercisemanagement.members.exception.handler;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.exercisemanagement.members.exception.JwtKeyNotFoundException;
import com.exercisemanagement.members.exception.MemberNotFoundException;
import com.exercisemanagement.members.exception.NicknameAlreadyExistsException;
import com.exercisemanagement.members.exception.ReservedNicknameException;
import com.exercisemanagement.members.exception.ReservedUserIdException;
import com.exercisemanagement.members.exception.UserIdAlreadyExistsException;

/**
 * 회원 API 예외 핸들러. 모든 예외를 코드·상태·메시지의 JSON으로 응답한다.
 */
@Order(1)
@RestControllerAdvice(basePackages = "com.exercisemanagement.members.controller")
public class MemberAPIControllerExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(MemberAPIControllerExceptionHandler.class);

    /**
     * 400 Bad Request – @Valid 유효성 검증 실패.
     * 실패한 필드명과 메시지를 Map으로 담아 반환한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {

        logger.info("MemberAPIControllerExceptionHandler.handleValidationExceptions()");

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * 401 Unauthorized – 필수 JWT 쿠키 누락.
     * @CookieValue(required = true)인 Authorization 쿠키가 없을 때 발생.
     */
    @ExceptionHandler(MissingRequestCookieException.class)
    public ResponseEntity<?> handleMissingCookie(MissingRequestCookieException ex) {
        logger.warn("JWT 쿠키 누락: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "로그인 정보가 없습니다. 다시 로그인해주세요.");
    }

    /**
     * 422 Unprocessable Entity – 예약어 ID 사용 시도.
     * 409(DB 중복)와 구분되는 정책적 거부이므로 422를 반환한다.
     */
    @ExceptionHandler(ReservedUserIdException.class)
    public ResponseEntity<?> handleReservedUserId(ReservedUserIdException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /**
     * 422 Unprocessable Entity – 예약어 닉네임 사용 시도.
     */
    @ExceptionHandler(ReservedNicknameException.class)
    public ResponseEntity<?> handleReservedNickname(ReservedNicknameException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /**
     * 409 Conflict – 아이디 중복.
     */
    @ExceptionHandler(UserIdAlreadyExistsException.class)
    public ResponseEntity<?> handleUserIdDuplicate(UserIdAlreadyExistsException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 409 Conflict – 닉네임 중복.
     */
    @ExceptionHandler(NicknameAlreadyExistsException.class)
    public ResponseEntity<?> handleNicknameDuplicate(NicknameAlreadyExistsException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 404 Not Found – 회원을 찾을 수 없음.
     */
    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<?> handleMemberNotFound(MemberNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * 401 Unauthorized – JWT 서명 키 없음.
     */
    @ExceptionHandler(JwtKeyNotFoundException.class)
    public ResponseEntity<?> handleJwtKeyNotFound(JwtKeyNotFoundException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * 공통 응답 포맷. 서버는 에러 코드와 메시지만 제공하고 이후 처리(페이지 이동 등)는 클라이언트가 한다.
     */
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("code", status.value());
        errorData.put("status", status.getReasonPhrase());
        errorData.put("message", message);
        return new ResponseEntity<>(errorData, status);
    }
}
