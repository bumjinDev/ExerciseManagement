package com.exercisemanagement.auth.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 인증 전용 엔티티. 회원 관리 테이블(membertbl)과 별개로
 * Spring Security 인증(JWT 발급·검증)에 필요한 userid, username, password, roles만 담는다.
 * 가입·수정 시 MemberService가 membertbl과 이 테이블을 함께 갱신한다.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "userentity")
public class AuthenticationEntity {

    @Id
    @Column(nullable = false)
    private String userid;      // 사용자 아이디

    @Column(nullable = false)
    private String username;    // 사용자 이름(닉네임)

    @Column(nullable = false)
    private String password;    // 비밀번호 BCrypt 해시

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "userentity_roles",
            joinColumns = @JoinColumn(name = "userid", referencedColumnName = "userid"))
    @Column(name = "roles")
    private List<String> roles; // 사용자 권한

    @PreRemove
    public void removeRoles() {
        roles.clear(); // 엔티티 삭제 전에 하위 테이블 데이터 삭제
    }
}
