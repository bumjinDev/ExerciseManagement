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
 * View(브라우저 페이지) 겸용 JWT 인증 실패 핸들러.
 * /api/ 경로 요청이면 401 JSON을, 그 외(브라우저 페이지) 요청이면 alert 스크립트를 반환한다.
 * 단계 5(UI)에서 뷰 필터체인이 생기면 그 체인에 등록해 사용한다. 현재 API 체인은
 * ApiAuthenticationEntryPoint를 사용한다.
 */
public class JwtAuthenticationFailureHandler implements AuthenticationEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFailureHandler.class);
    private static final String AUTH_COOKIE_NAME = "Authorization";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {

        logger.warn("JwtAuthenticationFailureHandler - 인증 실패 : {}", authException.getMessage());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        // Authorization 쿠키 삭제
        Cookie expiredCookie = new Cookie(AUTH_COOKIE_NAME, null);
        expiredCookie.setPath("/");
        expiredCookie.setMaxAge(0);
        response.addCookie(expiredCookie);

        String requestUri = request.getRequestURI();

        if (requestUri.contains("/api/")) {
            response.setContentType("application/json; charset=UTF-8");
            response.getWriter().write(
                    "{\"code\": 401, \"status\": \"Unauthorized\", \"message\": \"로그인이 필요합니다.\"}"
            );
        } else {
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().write("<script>alert('올바른 로그인 사용자가 아닙니다.'); history.back();</script>");
        }
    }
}
