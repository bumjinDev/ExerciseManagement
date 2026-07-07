package com.exercisemanagement.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.security.Key;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * JWT 토큰의 생성, 검증, 서명 키 관리 및 클레임(Claims) 조작 기능을 담당하는 유틸 클래스.
 * 서명 키는 환경 변수 JWT_SECRET_KEY(Base64 인코딩된 HMAC-SHA256 키)로 주입받는다.
 */
@Component
public class JWTUtil {

    // TODO: JWT 만료 시간은 테스트 편의를 위해 14일(jwt.expiration-ms)로 설정된 상태다. 운영 배포 전 재확정 대상.
    private final long expirationMs;
    private final Environment env;

    public JWTUtil(Environment env, @Value("${jwt.expiration-ms}") long expirationMs) {
        this.env = env;
        this.expirationMs = expirationMs;
    }

    /**
     * HMAC-SHA256 서명 키를 생성
     */
    public Key generateSigningKey() {

        String base64SecretKey = env.getProperty("JWT_SECRET_KEY");
        Objects.requireNonNull(base64SecretKey, "JWT_SECRET_KEY 환경 변수가 설정되지 않았습니다.");
        // 환경 변수 내 키 값은 Base64 인코딩 상태이므로 디코딩 적용.
        byte[] keyBytes = Base64.getDecoder().decode(base64SecretKey);

        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * Base64 인코딩된 키 문자열을 서명 키 객체로 디코딩해서 반환.
     */
    public Key getSigningKeyFromToken(String keyString) {
        return new SecretKeySpec(
                Base64.getDecoder().decode(keyString), SignatureAlgorithm.HS256.getJcaName());
    }

    /**
     * JWT 토큰을 생성
     */
    public String generateToken(String username, String userId, List<String> roles, Key key) {
        Date now = new Date();
        return Jwts.builder()
                .claim("username", username)
                .claim("userId", userId)
                .claim("roles", roles)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * JWT 검증
     */
    public boolean isValidToken(String token, Key key) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * JWT 토큰에서 모든 Claims 추출
     */
    public Claims extractAllClaims(String token, Key key) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            throw new AccessDeniedException("유효하지 않은 JWT입니다.");
        }
    }

    /**
     * JWT 토큰에서 특정 클레임 수정 후 새로운 토큰 생성 (예: 닉네임 변경 시 username 클레임 갱신)
     */
    public String modifyClaim(String token, String claimName, Object newValue) {
        Claims claims = extractAllClaims(token, getSigningKeyFromToken(getEnvironmentKey()));
        claims.put(claimName, newValue);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(new Date().getTime() + expirationMs))
                .signWith(getSigningKeyFromToken(getEnvironmentKey()))
                .compact();
    }

    /**
     * JWT 토큰에서 사용자 ID 추출
     */
    public String extractUserId(String token) {
        return extractAllClaims(token, getSigningKeyFromToken(getEnvironmentKey())).get("userId", String.class);
    }

    /**
     * JWT 토큰에서 사용자 이름 추출
     */
    public String extractUsername(String token) {
        return extractAllClaims(token, getSigningKeyFromToken(getEnvironmentKey())).get("username", String.class);
    }

    /**
     * JWT 토큰에서 역할 리스트 추출
     */
    public List<String> extractRoles(String token, Key key) {
        Claims claims = extractAllClaims(token, key);
        Object rolesObj = claims.get("roles");

        if (rolesObj instanceof List<?>) {
            return ((List<?>) rolesObj).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    /**
     * JWT 토큰의 만료 시간 추출
     */
    public Date extractExpiration(String token, Key key) {
        return extractAllClaims(token, key).getExpiration();
    }

    /**
     * JWT 토큰이 만료되었는지 확인
     */
    public Boolean isTokenExpired(String token, Key key) {
        return extractExpiration(token, key).before(new Date());
    }

    /**
     * JWT 토큰의 남은 유효 기간을 반환
     */
    public Duration getRemainingDuration(String token, Key key) {
        Date expiration = extractExpiration(token, key);
        long remainingMillis = expiration.getTime() - System.currentTimeMillis();
        return remainingMillis > 0 ? Duration.ofMillis(remainingMillis) : Duration.ZERO;
    }

    private String getEnvironmentKey() {
        return env.getProperty("JWT_SECRET_KEY");
    }
}
