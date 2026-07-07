package com.exercisemanagement.members.service;

import java.util.Map;

import com.exercisemanagement.members.model.MemberDTO;

public interface IMemberService {

    Map<String, String> validLoginSuccess(String jwt);  // 로그인 성공 후 JWT에서 사용자 정보 추출

    void validJoin(MemberDTO memberDTO);                // 회원 가입 (검증 포함)

    MemberDTO searchEditMember(String editId);          // 회원 정보 수정을 위한 회원 조회

    String editMember(String jwt, MemberDTO memberDTO); // 회원 정보 수정, 갱신된 JWT 반환
}
