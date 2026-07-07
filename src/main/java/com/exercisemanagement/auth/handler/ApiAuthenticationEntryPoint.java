package com.exercisemanagement.auth.handler;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API 전용 JWT 인증 실패 핸들러.
 * 인증 실패 시 Authorization 쿠키를 제거하고 401 JSON 응답을 반환한다.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);
    private static final String AUTH_COOKIE_NAME = "Authorization";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {

        logger.warn("ApiAuthenticationEntryPoint - API 인증 실패 : {}", authException.getMessage());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        // Authorization 쿠키 삭제
        Cookie expiredCookie = new Cookie(AUTH_COOKIE_NAME, null);
        expiredCookie.setPath("/");
        expiredCookie.setMaxAge(0);
        response.addCookie(expiredCookie);

        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write(
                "{\"code\": 401, \"status\": \"Unauthorized\", \"message\": \"로그인이 필요합니다.\"}"
        );
    }
}
