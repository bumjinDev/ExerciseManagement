package com.exercisemanagement.members.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.exercisemanagement.auth.entity.AuthenticationEntity;
import com.exercisemanagement.auth.repository.UserEntityRepository;
import com.exercisemanagement.members.model.MembersEntity;

/**
 * 회원 관리 도메인의 데이터 접근 계층.
 * 회원 정보(MembersEntity)와 인증 정보(AuthenticationEntity)를 함께 처리한다.
 * 가입·수정 시 회원 테이블과 인증 테이블을 동시에 저장·갱신한다.
 */
@Repository
public class MembersRepository implements IMembersRepository {

    private final MemberEntityRepository memberEntityRepository;
    private final UserEntityRepository userEntityRepository;

    public MembersRepository(MemberEntityRepository memberEntityRepository,
                             UserEntityRepository userEntityRepository) {
        this.memberEntityRepository = memberEntityRepository;
        this.userEntityRepository = userEntityRepository;
    }

    @Override
    public void addMember(MembersEntity membersEntity, AuthenticationEntity authenticationEntity) {
        memberEntityRepository.save(membersEntity);
        userEntityRepository.save(authenticationEntity);
    }

    @Override
    public Optional<MembersEntity> getMember(String userId) {
        return memberEntityRepository.findById(userId);
    }

    @Override
    public List<MembersEntity> getMembers(List<String> userIds) {
        return memberEntityRepository.findMembersByIds(userIds);
    }

    /**
     * 닉네임 중복 여부 검사 (본인 제외).
     * 중복된 회원이 존재하면 해당 회원을 반환한다(존재하지 않으면 empty).
     */
    @Override
    public Optional<MembersEntity> isNickNameEditAllowed(MembersEntity membersEntity) {
        return memberEntityRepository.findByNicknameAndNotIdNative(
                membersEntity.getNickName(),
                membersEntity.getId()
        );
    }

    @Override
    public void editMember(MembersEntity membersEntity, AuthenticationEntity authenticationEntity) {
        memberEntityRepository.save(membersEntity);
        userEntityRepository.save(authenticationEntity);
    }
}
