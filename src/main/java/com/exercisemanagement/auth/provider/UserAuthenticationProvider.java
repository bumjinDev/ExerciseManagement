package com.exercisemanagement.auth.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.exercisemanagement.auth.entity.AuthenticationEntity;
import com.exercisemanagement.auth.repository.UserEntityRepository;
import com.exercisemanagement.auth.service.UserEntityDetailService;
import com.exercisemanagement.auth.userdetails.UserEntityDetails;

/**
 * 로그인 인증 프로바이더.
 * userid로 인증 엔티티를 조회하고 BCrypt로 비밀번호를 검증한다.
 * 인증 성공 시 principal=username, details=userId 로 토큰을 구성한다(JWT 클레임 생성에 사용).
 */
public class UserAuthenticationProvider implements AuthenticationProvider {

    private static final Logger logger = LoggerFactory.getLogger(UserAuthenticationProvider.class);

    private final UserEntityDetailService userEntityDetailService;
    private final UserEntityRepository userEntityRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserAuthenticationProvider(
            UserEntityDetailService userEntityDetailService,
            UserEntityRepository userEntityRepository,
            BCryptPasswordEncoder passwordEncoder) {

        this.userEntityDetailService = userEntityDetailService;
        this.userEntityRepository = userEntityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {

        String userId = authentication.getPrincipal().toString();
        String password = authentication.getCredentials().toString();

        AuthenticationEntity userEntity = userEntityRepository.findByUserid(userId)
                .orElseThrow(() -> {
                    logger.warn("User ID '{}'를 찾을 수 없음", userId);
                    return new BadCredentialsException("Invalid User ID or Password");
                });

        UserDetails userDetails = userEntityDetailService.loadUserByUsername(userEntity.getUsername());

        if (!passwordEncoder.matches(password, userDetails.getPassword())) {
            logger.warn("잘못된 비밀번호 입력: 사용자 '{}'", userEntity.getUsername());
            throw new BadCredentialsException("Invalid User ID or Password");
        }

        logger.info("인증 성공: 사용자 '{}'", userEntity.getUsername());

        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(
                        userEntity.getUsername(),
                        password,
                        userDetails.getAuthorities());

        // JWT 생성 시 userId 클레임에 활용
        authenticationToken.setDetails(((UserEntityDetails) userDetails).getUserId());

        return authenticationToken;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
