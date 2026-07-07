package com.exercisemanagement.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

import com.exercisemanagement.auth.filter.JwtAuthProcessorFilter;
import com.exercisemanagement.auth.filter.LoginFilter;
import com.exercisemanagement.auth.handler.ApiAuthenticationEntryPoint;
import com.exercisemanagement.auth.handler.JwtAccessDeniedHandler;
import com.exercisemanagement.auth.provider.UserAuthenticationProvider;
import com.exercisemanagement.auth.repository.UserEntityRepository;
import com.exercisemanagement.auth.service.UserEntityDetailService;
import com.exercisemanagement.auth.util.CookieUtil;
import com.exercisemanagement.auth.util.JWTUtil;

/**
 * JWT 기반 무상태 인증 구성.
 * 모든 필터체인이 SessionCreationPolicy.STATELESS이며, 로그인 성공 시 JWT를
 * HttpOnly 쿠키(Authorization)로 발급하고 보호 경로에서 JwtAuthProcessorFilter가 검증한다.
 *
 * 챌린지 도메인(/api/challenges 등)의 권한 체계는 단계 3에서 별도 설계·보고 후 체인을 추가한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthenticationConfiguration authenticationConfiguration;
    private final UserEntityDetailService userEntityDetailService;
    private final UserEntityRepository userEntityRepository;
    private final JWTUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final Environment env;

    /** 로컬 개발 기준 false. HTTPS 배포 시 프로파일 설정으로 true 전환 */
    @Value("${app.cookie-secure}")
    private boolean cookieSecure;

    /** 로그인 성공 후 이동 경로(컨텍스트 경로 제외) */
    @Value("${app.login-success-path}")
    private String loginSuccessPath;

    public SecurityConfig(
            AuthenticationConfiguration authenticationConfiguration,
            UserEntityDetailService userEntityDetailService,
            UserEntityRepository userEntityRepository,
            JWTUtil jwtUtil,
            CookieUtil cookieUtil,
            Environment env) {
        this.authenticationConfiguration = authenticationConfiguration;
        this.userEntityDetailService = userEntityDetailService;
        this.userEntityRepository = userEntityRepository;
        this.jwtUtil = jwtUtil;
        this.cookieUtil = cookieUtil;
        this.env = env;
    }

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserAuthenticationProvider userAuthenticationProvider() {
        return new UserAuthenticationProvider(
                userEntityDetailService,
                userEntityRepository,
                bCryptPasswordEncoder()
        );
    }

    @Bean
    public AuthenticationManager authenticationManager() throws Exception {
        AuthenticationManager authenticationManager = authenticationConfiguration.getAuthenticationManager();
        ((ProviderManager) authenticationManager).getProviders().add(userAuthenticationProvider());
        return authenticationManager;
    }

    /* [로그인] : /login POST 요청을 LoginFilter가 처리 */
    @Bean
    public SecurityFilterChain loginFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/login")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAt(
                        new LoginFilter(authenticationManager(), jwtUtil, cookieSecure, loginSuccessPath),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /* [로그아웃] : Authorization 쿠키 제거 후 200 OK 반환 */
    @Bean
    public SecurityFilterChain logoutFilterChain(HttpSecurity http, CookieLogoutHandler cookieLogoutHandler) throws Exception {
        http.securityMatcher("/logout")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler(cookieLogoutHandler)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler())
                );
        return http.build();
    }

    /* [챌린지 도메인 API] : 목록·상세 조회만 공개, 나머지는 인증 필요.
     *   참가자·팀원 관계 같은 동적 인가는 도메인 서비스가 명세서 에러 코드로 판정한다.
     *   (docs/설계/권한체계_설계안.md — 사용자 승인) */
    @Bean
    public SecurityFilterChain challengeApiFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/challenges").permitAll()      // 6.5.1 목록
                        .requestMatchers(HttpMethod.GET, "/api/challenges/*").permitAll()    // 6.5.2 상세 (한 세그먼트만)
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAt(new JwtAuthProcessorFilter(cookieUtil, jwtUtil, env), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint())
                        .accessDeniedHandler(new JwtAccessDeniedHandler()));
        return http.build();
    }

    /* [회원 관리] : 가입은 공개, 로그인 성공 확인·정보 수정은 인증 필요 */
    @Bean
    public SecurityFilterChain membersServiceFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/members/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/members/loginSuccess").authenticated()
                        .requestMatchers(HttpMethod.GET, "/members/edit").authenticated()
                        .requestMatchers(HttpMethod.POST, "/members/edit").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/members/edit").authenticated()
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAt(new JwtAuthProcessorFilter(cookieUtil, jwtUtil, env), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint())
                        .accessDeniedHandler(new JwtAccessDeniedHandler()));
        return http.build();
    }
}
