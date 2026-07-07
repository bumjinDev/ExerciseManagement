package com.exercisemanagement.members.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exercisemanagement.members.model.MemberDTO;
import com.exercisemanagement.members.service.IMemberService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * 회원 관리 API 컨트롤러.
 * UI 기술 확정 전(단계 5)이므로 모든 응답은 JSON이다.
 */
@RestController
@RequestMapping("/members")
public class MembersAPIController {

    private static final Logger logger = LoggerFactory.getLogger(MembersAPIController.class);

    private final IMemberService memberService;
    private final boolean cookieSecure;

    public MembersAPIController(IMemberService memberService,
                                @Value("${app.cookie-secure}") boolean cookieSecure) {
        this.memberService = memberService;
        this.cookieSecure = cookieSecure;
    }

    /**
     * 회원 가입 요청 처리.
     * @Valid로 MemberDTO의 형식 검증을 수행하고, 도메인 검증(중복·예약어)은 서비스가 수행한다.
     */
    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> joinRequest(@Valid @RequestBody MemberDTO memberDTO) {

        logger.info("MembersAPIController.joinRequest() 호출");

        memberService.validJoin(memberDTO);
        return ResponseEntity.ok(Map.of("message", "회원가입이 정상적으로 완료되었습니다."));
    }

    /**
     * 로그인 성공 확인.
     * LoginFilter가 인증 성공 시 이 경로로 리다이렉트하며, JWT 쿠키에서 사용자 정보를 추출해 반환한다.
     */
    @GetMapping("/loginSuccess")
    public ResponseEntity<Map<String, String>> loginSuccess(
            @CookieValue(name = "Authorization", required = true) String jwt) {

        logger.info("MembersAPIController.loginSuccess() 호출");

        Map<String, String> sessionInfo = memberService.validLoginSuccess(jwt);
        return ResponseEntity.ok(sessionInfo);
    }

    /**
     * 회원 정보 수정을 위한 회원 조회.
     * 비밀번호 해시는 응답에 포함하지 않는다.
     */
    @GetMapping("/edit")
    public ResponseEntity<MemberDTO> searchEditMember(@RequestParam("editid") String editId) {

        logger.info("MembersAPIController.searchEditMember() 호출 - editid: {}", editId);

        MemberDTO memberDTO = memberService.searchEditMember(editId);
        memberDTO.setPw(null);
        return ResponseEntity.ok(memberDTO);
    }

    /**
     * 회원 정보 수정 요청 처리.
     * JWT 쿠키로 요청자를 식별하고, 수정 후 닉네임 클레임이 갱신된 JWT 쿠키를 재발급한다.
     */
    @PostMapping("/edit")
    public ResponseEntity<Map<String, Object>> editMember(
            @CookieValue(name = "Authorization", required = true) String jwt,
            @Valid @RequestBody MemberDTO editRequestDTO,
            HttpServletResponse httpResponse) {

        logger.info("MembersAPIController.editMember() 호출 - 사용자 ID: {}", editRequestDTO.getId());

        String editToken = memberService.editMember(jwt, editRequestDTO);

        Cookie cookie = new Cookie("Authorization", editToken);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setSecure(cookieSecure); // 로컬 개발 false, HTTPS 배포 시 app.cookie-secure=true로 전환
        httpResponse.addCookie(cookie);

        return ResponseEntity.ok(Map.of("message", "회원 수정이 정상적으로 완료되었습니다."));
    }
}
