package com.exercisemanagement.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그아웃 시 JWT 쿠키(Authorization)를 즉시 만료시켜 제거하는 핸들러.
 */
@Component
public class CookieLogoutHandler implements LogoutHandler {

    private static final Logger logger = LoggerFactory.getLogger(CookieLogoutHandler.class);

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {

        Cookie cookie = new Cookie("Authorization", null);
        cookie.setPath("/");
        cookie.setMaxAge(0); // 즉시 만료
        response.addCookie(cookie);

        logger.info("Authorization 쿠키 삭제 완료");
        if (authentication != null) {
            logger.info("로그아웃 사용자: {}", authentication.getName());
        }
    }
}
