package com.exercisemanagement.auth.filter;

import java.io.IOException;
import java.security.Key;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.exercisemanagement.auth.util.CookieUtil;
import com.exercisemanagement.auth.util.JWTUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 보호 경로 요청의 쿠키에서 JWT를 추출·검증해 SecurityContext에 인증을 설정하는 필터.
 * 토큰이 없거나 유효하지 않으면 인증 없이 체인을 계속 진행하고,
 * 이후 인가 단계에서 EntryPoint(401)/AccessDeniedHandler(403)가 응답한다.
 */
public class JwtAuthProcessorFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthProcessorFilter.class);
    private static final String AUTH_COOKIE_NAME = "Authorization";

    private final CookieUtil cookieUtil;
    private final JWTUtil jwtUtil;
    private final Environment env;

    public JwtAuthProcessorFilter(CookieUtil cookieUtil, JWTUtil jwtUtil, Environment env) {
        this.cookieUtil = cookieUtil;
        this.jwtUtil = jwtUtil;
        this.env = env;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 쿠키에서 JWT 추출
        Optional<String> tokenOpt = Optional.ofNullable(
                cookieUtil.extractJwtFromCookies(request.getCookies(), AUTH_COOKIE_NAME));

        if (tokenOpt.isEmpty()) {
            logger.debug("요청에 JWT 토큰이 존재하지 않음: {} {}", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        String token = tokenOpt.get();
        Key key = jwtUtil.getSigningKeyFromToken(env.getProperty("JWT_SECRET_KEY"));

        // 2. JWT 검증
        if (!jwtUtil.isValidToken(token, key)) {
            logger.warn("JWT 토큰 검증 실패");
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 사용자 ID 및 권한 추출 후 SecurityContext에 인증 설정
        String userId = jwtUtil.extractUserId(token);

        var authorities = jwtUtil.extractRoles(token, key).stream()
                .map(SimpleGrantedAuthority::new).toList();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        logger.debug("JWT 인증 성공: 사용자 ID = {}", userId);
        filterChain.doFilter(request, response);
    }
}
