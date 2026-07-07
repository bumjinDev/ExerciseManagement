package com.exercisemanagement.members.dao;

import java.util.List;
import java.util.Optional;

import com.exercisemanagement.auth.entity.AuthenticationEntity;
import com.exercisemanagement.members.model.MembersEntity;

public interface IMembersRepository {

    void addMember(MembersEntity membersEntity, AuthenticationEntity authenticationEntity);   // 회원 가입: 회원·인증 테이블 동시 저장

    Optional<MembersEntity> getMember(String userId);                                         // 회원 단건 조회

    List<MembersEntity> getMembers(List<String> userIds);                                     // 다중 회원 조회

    void editMember(MembersEntity membersEntity, AuthenticationEntity authenticationEntity);  // 회원 정보 수정: 두 테이블 동시 갱신

    Optional<MembersEntity> isNickNameEditAllowed(MembersEntity membersEntity);               // 본인 제외 닉네임 중복 검사
}
