# API Application Matrix

## Utility

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `GET /health` | 서버 상태 확인 | 앱 기능 API는 아니므로 유지 권장 |
| iOS 미적용 | `GET /members/statistics/excel` | 회원 통계 엑셀 파일 | iOS 불필요. 관리자/운영용이면 유지 |

## Member Auth

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `POST /api/member/auth/sns-login` | SNS 로그인 및 자동 회원가입 | 유지 |
| iOS 적용 | `POST /api/member/auth/logout` | 로그아웃 | 유지 |
| iOS 미적용 | `POST /api/member/auth/jwt-token-login` | refresh token 자동 로그인/토큰 재발급 | 적용 검토. 토큰 만료 대응에 필요 |

## Member Account

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `GET /api/member/account/me` | 계정 정보 조회 | 현재 iOS는 profile/home API로 대체 중. 필요성 낮음 |
| iOS 미적용 | `POST /api/member/account/email/check` | 이메일 중복 확인 | SNS 로그인 중심이면 필요성 낮음 |
| iOS 미적용 | `POST /api/member/account/email/change` | 이메일 변경 | SNS 로그인 중심이면 필요성 낮음 |
| iOS 미적용 | `POST /api/member/account/password/change` | 비밀번호 변경 | COMMON 로그인 비활성이라 필요성 낮음 |
| iOS 적용 | `POST /api/member/account/withdraw` | 회원 탈퇴 | 유지 |
| iOS 적용 | `POST /api/member/account/home/memberInfo` | 홈 화면 회원 정보 | 유지 |

## Member Profile

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `POST /api/member/profile/nickName_duplicate_check` | 닉네임 중복 검사 | 유지 |
| iOS 미적용 | `POST /api/member/profile/curation_profile_save` | 큐레이션용 프로필 저장 | 정리 후보. 초기 큐레이션 API가 대체 |
| iOS 적용 | `GET /api/member/profile/profile` | 회원 프로필 조회 | 유지 |
| iOS 적용 | `PUT /api/member/profile/profile` | 회원 프로필 수정 | 유지 |
| iOS 미적용 | `POST /api/member/profile/member_job_update` | 직업 대분류 설정 | 정리 후보. iOS는 `PUT /profile`로 jobId 수정 |
| iOS 미적용 | `POST /api/member/profile/member_job_detaile_update` | 직업 상세 설정 | 정리 후보. iOS는 `PUT /profile`로 jobDetailId 수정 |

## Member Setting

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `GET /api/member/setting/settings` | 회원 세팅 조회 | 적용 검토. 서버에 알림/테마 설정을 저장하려면 필요 |
| iOS 미적용 | `PUT /api/member/setting/update-settings` | 회원 세팅 수정 | 적용 검토 |
| iOS 미적용 | `GET /api/member/setting/mission-reminder` | 미션 리마인더 조회 | 적용 검토 또는 중복 정리 |

## Member Curation

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `POST /api/member/curation/chatbot-setup` | 챗봇 온보딩 초기 설정 | 유지 |
| iOS 적용 | `POST /api/member/curation/initial` | 초기 회원 큐레이션 SSE | 유지 |

## Chatbot Curation

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `POST /api/member/curation/chatbot/start` | 목표 설정 챗봇 시작 | 유지 |
| iOS 적용 | `POST /api/member/curation/chatbot/answer` | 챗봇 답변 제출 | 유지 |

## Goal

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `GET /api/member/goal` | 현재 활성 목표 조회 | 적용 검토. 목표 표시/수정 화면이 있으면 필요 |

## Character

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `GET /api/character/list` | 캐릭터 목록 조회 | 적용 검토. 현재 iOS는 로컬 선택값 사용 |
| iOS 미적용 | `POST /api/character/selected` | 회원 캐릭터 선택 | 정리 또는 적용 검토. 현재 `chatbot-setup`, `initial`에서 캐릭터 생성 흐름 사용 |

## Job

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `GET /api/job/getJobList` | 직업 대분류 조회 | 유지 |
| iOS 적용 | `GET /api/job/getJobDetailList` | 직업 상세 조회 | 유지 |

## Interest

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `POST /api/interests/recommend` | AI/RAG 관심사 추천 | 적용 검토. 현재 iOS는 tree 조회 중심 |
| iOS 적용 | `GET /api/interests/member` | 멤버 관심사 조회 | 유지 |
| iOS 적용 | `PUT /api/interests/member/{memberInterestId}` | 멤버 관심사 수정 | 유지 |
| iOS 미적용 | `DELETE /api/interests/member/{memberInterestId}` | 멤버 관심사 삭제 | 적용 검토. 관심사 편집에서 삭제 기능이 있으면 필요 |
| iOS 적용 | `GET /api/interests/data` | 시스템 관심사 목록 조회 | 유지 |
| iOS 미적용 | `POST /api/interests/member` | 멤버 관심사 등록 | 적용 검토. 초기 큐레이션은 `/curation/initial`이 대체 |
| iOS 정의만 | `POST /api/interests/interest/validation` | 관심사 문자열 검증 | 실제 화면 호출 여부 확인 필요. 정의/서비스는 있음 |

## Mission Recommendation

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `POST /api/missions/recommend` | AI 기반 미션 추천 생성 | 유지 |

## Member Mission

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `GET /api/member/mission` | 멤버 미션 목록 조회 | 유지 |
| iOS 적용 | `GET /api/member/mission/completion-status` | 기간별 완료 여부 조회 | 유지 |
| iOS 적용 | `PUT /api/member/mission/status` | 미션 상태 변경 | 유지. 단, 소유자 검증 보강 필요 |
| iOS 적용 | `POST /api/member/mission/select` | 추천 미션 선택 | 유지 |
| iOS 적용 | `GET /api/member/mission/recommend` | 오늘의 미션 추천 조회 | 유지 |
| iOS 적용 | `POST /api/member/mission/retry` | 오늘의 미션 재추천 | 유지 |
| iOS 미적용 | `DELETE /api/member/mission/reset/{memberInterestId}` | 특정 관심사 미션 리셋 | 정리 후보 또는 내부/admin 제한 |
| iOS 미적용 | `POST /api/member/mission/retry-count/reset` | 재추천 횟수 초기화 | 정리 후보 또는 내부/admin 제한 |
| iOS 적용 | `GET /api/member/mission/continue/mission/month/{targetMonth}` | 월별 수행 현황 | 유지 |
| iOS 적용 | `GET /api/member/mission/continue/mission/month` | 월별 완료일 수 | 유지 |

## Custom Mission

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 미적용 | `POST /api/member/custom-mission` | 커스텀 미션 생성 | 적용 검토. 직접 미션 추가 기능이 있으면 필요 |
| iOS 미적용 | `GET /api/member/custom-mission` | 커스텀 미션 조회 | 적용 검토 |
| iOS 미적용 | `PUT /api/member/custom-mission/status` | 커스텀 미션 상태 변경 | 적용 검토 |
| iOS 미적용 | `PUT /api/member/custom-mission/content` | 커스텀 미션 내용 수정 | 적용 검토 |
| iOS 미적용 | `DELETE /api/member/custom-mission/{customMissionId}` | 커스텀 미션 삭제 | 적용 검토 |

## Ranking

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `GET /api/ranking/popular` | 인기 미션 랭킹 조회 | 유지 |
| iOS 미적용 | `POST /api/ranking/batch` | 랭킹 배치 수동 실행 | iOS 불필요. 내부/admin 제한 권장 |
| iOS 미적용 | `GET /api/ranking/age-groups` | 연령대 목록 조회 | 적용 검토. 현재 iOS 필터가 하드코딩이면 동적화 가능 |

## Notification

| Status | API | 기능 | 판단 |
|---|---|---|---|
| iOS 적용 | `POST /api/notifications/token` | 푸시 토큰 등록 | 유지. Body memberId 대신 JWT 사용하도록 개선 권장 |
| iOS 정의만 | `POST /api/notifications/test/send` | 테스트 푸시 발송 | 정리 후보. 운영 노출 위험, admin/internal 제한 권장 |
