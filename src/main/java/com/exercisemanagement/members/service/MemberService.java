package com.exercisemanagement.members.service;

import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exercisemanagement.auth.entity.AuthenticationEntityConverter;
import com.exercisemanagement.auth.repository.UserEntityRepository;
import com.exercisemanagement.auth.util.JWTUtil;
import com.exercisemanagement.members.dao.IMembersRepository;
import com.exercisemanagement.members.dao.MemberEntityRepository;
import com.exercisemanagement.members.exception.MemberNotFoundException;
import com.exercisemanagement.members.exception.NicknameAlreadyExistsException;
import com.exercisemanagement.members.exception.ReservedNicknameException;
import com.exercisemanagement.members.exception.ReservedUserIdException;
import com.exercisemanagement.members.exception.UserIdAlreadyExistsException;
import com.exercisemanagement.members.model.MemberConverter;
import com.exercisemanagement.members.model.MemberDTO;
import com.exercisemanagement.members.model.MembersEntity;

/**
 * 회원 관련 핵심 비즈니스 로직.
 * 회원 가입, 정보 수정, JWT 클레임 갱신을 제공하며,
 * 회원 테이블(membertbl)과 인증 테이블(userentity)을 함께 갱신한다.
 */
@Service
public class MemberService implements IMemberService {

    private static final Logger logger = LoggerFactory.getLogger(MemberService.class);

    private final IMembersRepository membersRepository;
    private final MemberEntityRepository memberEntityRepository;
    private final UserEntityRepository userEntityRepository;
    private final JWTUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    private final MemberConverter memberConverter;
    private final AuthenticationEntityConverter authenticationEntityConverter;

    /**
     * 사용 불가능한 예약어 ID·닉네임 목록.
     * 시스템 관리자, 관리 기능, 테스트 계정 등과 혼동될 수 있는 값을 정책적으로 차단한다.
     * 대소문자 우회 방지를 위해 비교 시 toLowerCase() 변환 후 대조한다.
     */
    private static final Set<String> RESERVED_IDS = Set.of(
            "admin", "root", "system", "administrator", "superuser",
            "test", "null", "undefined", "master", "operator"
    );

    public MemberService(
            IMembersRepository membersRepository,
            MemberEntityRepository memberEntityRepository,
            UserEntityRepository userEntityRepository,
            JWTUtil jwtUtil,
            BCryptPasswordEncoder passwordEncoder,
            MemberConverter memberConverter,
            AuthenticationEntityConverter authenticationEntityConverter) {

        this.membersRepository = membersRepository;
        this.memberEntityRepository = memberEntityRepository;
        this.userEntityRepository = userEntityRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.memberConverter = memberConverter;
        this.authenticationEntityConverter = authenticationEntityConverter;
    }

    /**
     * 로그인 성공 시 JWT에서 사용자 ID와 닉네임을 추출하여 반환.
     */
    @Override
    public Map<String, String> validLoginSuccess(String jwt) {
        logger.info("MemberService.validLoginSuccess()");

        Map<String, String> loginSuccessInfo = new HashMap<>();
        loginSuccessInfo.put("userId", jwtUtil.extractUserId(jwt));
        loginSuccessInfo.put("userName", jwtUtil.extractUsername(jwt));

        return loginSuccessInfo;
    }

    /**
     * 회원 가입 요청 처리.
     * 예약어 검증, ID·닉네임 중복 검증, 비밀번호 암호화 후
     * 회원 테이블과 인증 테이블에 사용자 정보를 등록한다(기본 권한 ROLE_USER).
     *
     * @throws ReservedUserIdException 예약어 ID 사용 시도
     * @throws ReservedNicknameException 예약어 닉네임 사용 시도
     * @throws UserIdAlreadyExistsException 아이디 중복
     * @throws NicknameAlreadyExistsException 닉네임 중복
     */
    @Transactional
    @Override
    public void validJoin(MemberDTO memberDTO) {
        logger.info("MemberService.validJoin()");

        if (RESERVED_IDS.contains(memberDTO.getId().toLowerCase())) {
            throw new ReservedUserIdException("\"" + memberDTO.getId() + "\"은(는) 사용할 수 없는 아이디입니다.");
        }

        if (RESERVED_IDS.contains(memberDTO.getNickName().toLowerCase())) {
            throw new ReservedNicknameException("\"" + memberDTO.getNickName() + "\"은(는) 사용할 수 없는 닉네임입니다.");
        }

        if (memberEntityRepository.findById(memberDTO.getId()).isPresent()) {
            throw new UserIdAlreadyExistsException("이미 사용 중인 아이디입니다.");
        }
        if (memberEntityRepository.findByNickName(memberDTO.getNickName()).isPresent()) {
            throw new NicknameAlreadyExistsException("이미 사용 중인 닉네임입니다.");
        }

        memberDTO.setPw(passwordEncoder.encode(memberDTO.getPw()));
        memberDTO.setJoinDate(new Date(System.currentTimeMillis()));

        membersRepository.addMember(
                memberConverter.toEntity(memberDTO),
                authenticationEntityConverter.toEntity(memberDTO, List.of("ROLE_USER"))
        );
    }

    /**
     * 회원 정보 수정 화면을 위한 회원 조회.
     *
     * @throws MemberNotFoundException 존재하지 않는 사용자 ID
     */
    @Override
    public MemberDTO searchEditMember(String editId) {
        return memberConverter.toDTO(membersRepository.getMember(editId)
                .orElseThrow(() -> new MemberNotFoundException("현재 존재하지 않는 사용자 ID 입니다")));
    }

    /**
     * 회원 정보 수정 처리.
     * 닉네임 예약어 검증, 본인 제외 닉네임 중복 검사, 비밀번호 암호화,
     * 두 테이블 갱신 및 JWT username 클레임 갱신을 수행한다.
     *
     * @return 닉네임 클레임이 갱신된 새 JWT
     * @throws ReservedNicknameException 예약어 닉네임 사용 시도
     * @throws MemberNotFoundException 사용자 ID가 존재하지 않는 경우
     * @throws NicknameAlreadyExistsException 닉네임 중복
     */
    @Transactional
    @Override
    public String editMember(String currentToken, MemberDTO memberDTO) {
        logger.info("MemberService.editMember()");

        if (RESERVED_IDS.contains(memberDTO.getNickName().toLowerCase())) {
            throw new ReservedNicknameException("\"" + memberDTO.getNickName() + "\"은(는) 사용할 수 없는 닉네임입니다.");
        }

        List<String> roles = userEntityRepository.findById(memberDTO.getId())
                .orElseThrow(() -> new MemberNotFoundException("현재 존재하지 않는 사용자 ID 입니다"))
                .getRoles();

        // 가입일은 수정 대상이 아니므로 기존 값을 유지한다
        Date joinDate = membersRepository.getMember(memberDTO.getId())
                .orElseThrow(() -> new MemberNotFoundException("현재 존재하지 않는 사용자 ID 입니다"))
                .getJoinDate();

        memberDTO.setPw(passwordEncoder.encode(memberDTO.getPw()));
        memberDTO.setJoinDate(joinDate);

        Optional<MembersEntity> duplicated = membersRepository.isNickNameEditAllowed(
                memberConverter.toEntity(memberDTO));

        if (duplicated.isPresent()) {
            throw new NicknameAlreadyExistsException("닉네임이 중복되어 수정할 수 없습니다.");
        }

        membersRepository.editMember(
                memberConverter.toEntity(memberDTO),
                authenticationEntityConverter.toEntity(memberDTO, roles)
        );

        return jwtUtil.modifyClaim(currentToken, "username", memberDTO.getNickName());
    }
}
