package com.exercisemanagement.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.auth.entity.AuthenticationEntity;
import com.exercisemanagement.auth.repository.UserEntityRepository;
import com.exercisemanagement.auth.userdetails.UserEntityDetails;

@Service
public class UserEntityDetailService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(UserEntityDetailService.class);

    private final UserEntityRepository userRepository;

    public UserEntityDetailService(UserEntityRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        logger.info("loadUserByUsername() 호출 - username: {}", username);

        AuthenticationEntity authenticationEntity = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    logger.warn("사용자를 찾을 수 없음: {}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });

        return new UserEntityDetails(authenticationEntity);
    }
}
