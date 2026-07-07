package com.exercisemanagement.auth.entity;

import org.springframework.stereotype.Component;

import com.exercisemanagement.members.model.MemberDTO;

import java.util.List;

@Component
public class AuthenticationEntityConverter {

    /**
     * MemberDTO -> AuthenticationEntity 변환 (권한 포함)
     * 패스워드는 호출 전에 BCrypt로 암호화되어 있어야 한다.
     */
    public AuthenticationEntity toEntity(MemberDTO dto, List<String> roles) {
        if (dto == null) {
            return null;
        }

        return AuthenticationEntity.builder()
                .userid(dto.getId())
                .username(dto.getNickName())
                .password(dto.getPw())
                .roles(roles)
                .build();
    }
}
