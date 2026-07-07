package com.exercisemanagement.auth.util;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;

/**
 * JWT를 쿠키에서 추출하는 유틸리티 클래스.
 */
@Component
public class CookieUtil {

    /**
     * HTTP 요청의 쿠키 배열에서 특정 이름을 가진 JWT를 추출한다.
     *
     * @param cookies    HTTP 요청의 쿠키 배열
     * @param cookieName JWT가 저장된 쿠키 이름
     * @return JWT 문자열 (쿠키가 없거나 해당 이름의 쿠키가 없으면 null)
     */
    public String extractJwtFromCookies(Cookie[] cookies, String cookieName) {
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }
}
