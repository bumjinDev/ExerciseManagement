package com.exercisemanagement.members.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.exercisemanagement.members.model.MembersEntity;

public interface MemberEntityRepository extends JpaRepository<MembersEntity, String> {

    Optional<MembersEntity> findById(String userId);            // 가입 시 아이디 중복 확인

    Optional<MembersEntity> findByNickName(String nickName);    // 가입 시 닉네임 중복 확인

    // 회원 수정 시 본인을 제외한 닉네임 중복 확인
    @Query(value = "SELECT * FROM membertbl WHERE nickname = :nickname AND id != :id", nativeQuery = true)
    Optional<MembersEntity> findByNicknameAndNotIdNative(@Param("nickname") String nickname, @Param("id") String id);

    @Query("SELECT m FROM MembersEntity m WHERE m.id IN :userIds")
    List<MembersEntity> findMembersByIds(@Param("userIds") List<String> userIds);
}
