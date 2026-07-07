package com.exercisemanagement.members.model;

import java.sql.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 회원 관리 테이블(membertbl) 엔티티.
 * 컬럼 매핑은 PhysicalNamingStrategyStandardImpl(필드명 그대로) 기준이다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Table(name = "membertbl")
public class MembersEntity {

    @Id
    String id;          // 아이디
    String pw;          // 비밀번호 (BCrypt 해시)
    String nickName;    // 회원 닉네임
    String tel;         // 회원 전화번호
    String email;       // 이메일
    Date joinDate;      // 회원 가입 날짜
}
