package com.project.cinemory.domain.user.dto;

import java.util.List;

/**
 * 설정 화면의 "연결된 계정" 목록.
 *
 * @param hasPassword 로컬 가입자인가. 앱이 해제 버튼 비활성화(마지막 인증 수단)를 판단하는 데 쓴다 —
 *                    최종 판정은 서버가 한다({@code LAST_AUTH_METHOD})
 */
public record SocialAccountsResponse(boolean hasPassword, List<SocialAccountResponse> socialAccounts) {
}
