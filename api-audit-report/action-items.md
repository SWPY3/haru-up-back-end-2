# Action Items

## iOS에 적용을 검토할 API

우선순위는 실제 앱 안정성/사용자 기능 영향 기준입니다.

### 1. 토큰 재발급

- `POST /api/member/auth/jwt-token-login`
- 현재 iOS는 로그인 후 토큰을 저장하지만 refresh token 기반 재발급 호출은 확인되지 않았습니다.
- access token 만료 시 자동 로그인/세션 유지가 깨질 수 있으므로 적용 우선순위가 높습니다.

### 2. 회원 설정

- `GET /api/member/setting/settings`
- `PUT /api/member/setting/update-settings`
- `GET /api/member/setting/mission-reminder`
- iOS 알림 설정 화면은 현재 OS 권한 중심으로 보입니다.
- 서버에 push, email, marketing, theme 값을 저장해야 한다면 연결이 필요합니다.
- `settings`와 `mission-reminder`가 같은 DTO를 반환하므로 중복 API인지 확인이 필요합니다.

### 3. 목표 조회

- `GET /api/member/goal`
- 목표 챗봇 완료 후 현재 목표를 마이페이지/홈/히스토리에서 보여줄 계획이면 적용 필요합니다.

### 4. 관심사 등록/삭제

- `POST /api/interests/member`
- `DELETE /api/interests/member/{memberInterestId}`
- 초기 큐레이션은 `POST /api/member/curation/initial` 안에서 관심사를 저장합니다.
- 마이페이지 관심사 편집에서 "새 관심사 추가" 또는 "삭제" 기능을 제공하려면 별도 적용이 필요합니다.

### 5. 커스텀 미션

- `POST /api/member/custom-mission`
- `GET /api/member/custom-mission`
- `PUT /api/member/custom-mission/status`
- `PUT /api/member/custom-mission/content`
- `DELETE /api/member/custom-mission/{customMissionId}`
- iOS에 직접 미션 추가 UI가 있거나 추가 예정이면 연결 필요합니다.
- 현재 네트워크 연결은 확인되지 않았습니다.

### 6. 랭킹 필터 동적 데이터

- `GET /api/ranking/age-groups`
- iOS 필터 UI가 하드코딩이면 꼭 필요하지는 않습니다.
- 백엔드 enum 기준으로 동기화하려면 적용하면 좋습니다.

## 불필요하거나 정리 후보인 API

삭제 전에는 반드시 클라이언트 배포 버전, 운영 사용 여부, 관리자 사용 여부를 확인해야 합니다.

### 레거시/중복 가능성 높음

- `POST /api/member/profile/curation_profile_save`
  - 초기 큐레이션은 `POST /api/member/curation/initial`이 담당합니다.
  - 챗봇 온보딩은 `POST /api/member/curation/chatbot-setup`이 캐릭터/닉네임 저장을 담당합니다.

- `POST /api/member/profile/member_job_update`
- `POST /api/member/profile/member_job_detaile_update`
  - iOS는 프로필 수정에서 `PUT /api/member/profile/profile`로 `jobId`, `jobDetailId`를 보냅니다.
  - 별도 job update endpoint는 중복 가능성이 큽니다.

### 모바일 앱에는 불필요, 내부/admin 전용 권장

- `POST /api/ranking/batch`
  - 스케줄러가 `RankingBatchScheduler`에서 직접 배치를 실행합니다.
  - 공개 사용자 JWT로 호출 가능한 API라면 admin/internal 제한을 권장합니다.

- `POST /api/notifications/test/send`
  - 테스트 푸시 발송 API입니다.
  - 운영 환경에서는 제거하거나 admin/internal 전용으로 제한하는 것이 안전합니다.

- `GET /members/statistics/excel`
  - iOS에는 불필요합니다.
  - 관리자 다운로드 기능이면 별도 권한 체계가 필요합니다.

### 디버그/운영 편의 API 가능성

- `DELETE /api/member/mission/reset/{memberInterestId}`
- `POST /api/member/mission/retry-count/reset`
  - iOS 호출은 확인되지 않았습니다.
  - 사용자 기능으로 필요한지, 운영/디버그용인지 확인 후 정리해야 합니다.

## 보안/품질 개선 후보

### 미션 상태 변경 소유자 검증

- `PUT /api/member/mission/status`
- iOS 적용 API입니다.
- 컨트롤러는 `principal`을 받지만 서비스 호출은 `memberMissionId` 중심입니다.
- 요청자가 해당 미션 소유자인지 검증하는 로직이 필요합니다.

### 푸시 토큰 등록

- `POST /api/notifications/token`
- iOS 적용 API입니다.
- 현재 iOS는 Body에 `memberId`를 보냅니다.
- 백엔드 TODO 주석처럼 JWT에서 memberId를 가져오도록 바꾸는 것이 안전합니다.

### 챗봇 답변 세션 검증

- `POST /api/member/curation/chatbot/answer`
- iOS 적용 API입니다.
- 컨트롤러에서 `principal`을 받지만 usecase 호출에 사용하지 않습니다.
- Redis 세션의 memberId와 로그인 사용자의 memberId가 같은지 검증하는 것이 안전합니다.

## 삭제 전 확인 체크리스트

- 운영 서버 로그에서 최근 30일 호출 여부 확인
- iOS 최신 배포 버전뿐 아니라 이전 배포 버전의 API 사용 여부 확인
- 관리자/운영자가 Swagger나 Postman으로 쓰는 API인지 확인
- 배치/스케줄러가 endpoint가 아니라 서비스 계층을 직접 쓰는지 확인
- 삭제 대신 먼저 deprecate, admin 제한, feature flag 적용 가능성 확인
