package com.exercisemanagement.members.model;

import java.sql.Date;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MemberDTO {

    @NotBlank(message = "아이디는 필수 입력값입니다.")
    @Size(min = 4, max = 20, message = "아이디는 4자 이상 20자 이하로 입력해야 합니다.")
    private String id;

    @NotBlank(message = "비밀번호는 필수 입력값입니다.")
    @Size(min = 8, message = "비밀번호는 최소 8자 이상이어야 합니다.")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).+$",
        message = "비밀번호는 영문 대문자, 소문자, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다."
    )
    private String pw;

    @NotBlank(message = "닉네임은 필수 입력값입니다.")
    @Size(max = 20, message = "닉네임은 최대 20자까지 가능합니다.")
    private String nickName;

    @NotBlank(message = "전화번호는 필수 입력값입니다.")
    @Pattern(regexp = "^(010|011|016|017|018|019)-?\\d{3,4}-?\\d{4}$",
             message = "유효한 전화번호 형식이 아닙니다. 예: 010-1234-5678")
    private String tel;

    @NotBlank(message = "이메일은 필수 입력값입니다.")
    @Email(message = "유효한 이메일 주소 형식이 아닙니다.")
    @Pattern(
        regexp = "^[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
        message = "이메일 도메인 형식이 올바르지 않습니다. 예: example@gmail.com"
    )
    private String email;

    private Date joinDate;  // 서버 내부에서 설정되므로 별도 검증 불필요
}
