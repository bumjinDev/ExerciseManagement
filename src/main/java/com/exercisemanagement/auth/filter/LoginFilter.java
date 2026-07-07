package com.exercisemanagement.auth.filter;

import java.io.IOException;
import java.security.Key;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.exercisemanagement.auth.handler.LoginAuthenticationFailureHandler;
import com.exercisemanagement.auth.util.JWTUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 로그인(/login) 요청 처리 필터.
 * JSON(application/json)과 폼(application/x-www-form-urlencoded) 두 형식의
 * 아이디(userid)·비밀번호(password) 요청을 모두 받는다.
 * 인증 성공 시 JWT를 HttpOnly 쿠키(Authorization)로 발급하고 로그인 성공 경로로 리다이렉트한다.
 */
public class LoginFilter extends UsernamePasswordAuthenticationFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoginFilter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JWTUtil jwtUtil;
    private final boolean cookieSecure;
    private final String successRedirectPath;

    public LoginFilter(AuthenticationManager authenticationManager, JWTUtil jwtUtil,
                       boolean cookieSecure, String successRedirectPath) {

        super.setAuthenticationManager(authenticationManager);
        this.setAuthenticationFailureHandler(new LoginAuthenticationFailureHandler());

        this.jwtUtil = jwtUtil;
        this.cookieSecure = cookieSecure;
        this.successRedirectPath = successRedirectPath;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {

        logger.info("LoginFilter: 로그인 요청 받음, Content-Type: {}", request.getContentType());

        String contentType = request.getContentType();
        LoginRequest loginRequest = null;

        try {
            if (contentType != null && contentType.contains("application/json")) {
                loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
            } else if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
                loginRequest = new LoginRequest(
                        request.getParameter("userid"),
                        request.getParameter("password"));
            }
        } catch (IOException e) {
            throw new AuthenticationServiceException("로그인 요청 본문을 읽을 수 없습니다.", e);
        }

        if (loginRequest == null || loginRequest.getUserid() == null || loginRequest.getPassword() == null) {
            throw new AuthenticationServiceException("지원하지 않는 로그인 요청 형식이거나 아이디·비밀번호가 없습니다.");
        }

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginRequest.getUserid(), loginRequest.getPassword());
        return getAuthenticationManager().authenticate(authToken);
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain, Authentication authResult) {
        logger.info("LoginFilter.successfulAuthentication - 사용자 : {}", authResult.getName());

        addJwtToCookie(response, generateJwt(authResult));

        // 로그인 성공 후 이동. 경로는 app.login-success-path 설정값(컨텍스트 경로 뒤에 붙는다)
        try {
            response.sendRedirect(request.getContextPath() + successRedirectPath);
        } catch (IOException e) {
            logger.error("로그인 성공 리다이렉트 실패", e);
        }
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response,
                                              AuthenticationException failed) throws IOException, ServletException {
        logger.warn("로그인 실패: {}", failed.getMessage());
        // LoginAuthenticationFailureHandler가 내부에서 자동 호출되어 처리
        super.unsuccessfulAuthentication(request, response, failed);
    }

    /**
     * 인증 결과로부터 JWT 생성
     */
    private String generateJwt(Authentication authResult) {

        String username = authResult.getName();
        String userId = (String) authResult.getDetails();

        List<String> roles = authResult.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        Key key = jwtUtil.generateSigningKey();
        return jwtUtil.generateToken(username, userId, roles, key);
    }

    /**
     * JWT 토큰을 응답 쿠키에 추가
     */
    private void addJwtToCookie(HttpServletResponse response, String jwtToken) {

        Cookie cookie = new Cookie("Authorization", jwtToken);
        cookie.setSecure(cookieSecure); // 로컬 개발 false, HTTPS 배포 시 app.cookie-secure=true로 전환
        cookie.setHttpOnly(true);       // JavaScript 접근 방지
        cookie.setPath("/");            // 전체 경로에서 유효
        response.addCookie(cookie);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class LoginRequest {
        String userid;
        String password;
    }
}
