# Backend Internal Usage

## 결론

백엔드가 자기 자신의 HTTP API endpoint를 호출하는 구조는 확인되지 않았습니다.

예를 들어 백엔드 내부에서 `GET /api/...`, `POST /api/...`를 `RestClient`, `WebClient`, `RestTemplate` 등으로 호출하는 코드는 보이지 않습니다.

다만 API와 같은 업무 기능을 서비스/유스케이스 계층에서 직접 재사용하는 흐름은 있습니다. 따라서 endpoint를 삭제하더라도 연결된 service/usecase까지 삭제하면 내부 배치나 큐레이션이 깨질 수 있습니다.

## 내부 재사용 흐름

### 랭킹 배치

- 외부 API: `POST /api/ranking/batch`
- 내부 사용: `RankingBatchScheduler`
- 내부 방식: `JobLauncher.run(rankingMissionDailyJob, jobParameters)`
- 판단: endpoint는 iOS 불필요지만, 배치 Job 자체는 유지 필요

### 목표 기반 미션 생성

- 외부 API 관련 흐름: `POST /api/member/curation/chatbot/answer`
- 내부 사용: `DailyMissionGenerationBatch`
- 내부 방식: `GoalBasedMissionGenerationService.generateAndSaveMissions(...)`
- 판단: chatbot endpoint는 iOS 적용, batch 서비스도 유지 필요

### 초기 큐레이션 관심사 저장 및 미션 추천

- 외부 API: `POST /api/member/curation/initial`
- 내부 사용: `MemberCurationUseCase`
- 내부 방식:
  - `MemberInterestService.saveInterests(...)`
  - `MissionRecommendService.recommendByMemberInterestIds(...)`
- 관련 endpoint:
  - `POST /api/interests/member`
  - `POST /api/missions/recommend`
- 판단: endpoint를 호출하는 것은 아니지만 같은 서비스 기능이 내부에서 사용됨

### 푸시 알림

- 외부 API: `POST /api/notifications/test/send`
- 내부 사용: `NotificationPushScheduler`
- 내부 방식: `NotificationUseCase.sendToMemberWithDeviceId(...)`
- 판단: 테스트 발송 endpoint는 정리 후보지만 NotificationUseCase와 scheduler는 유지 필요

## 외부 API 호출

백엔드가 호출하는 외부 API는 있습니다.

- Clova Chat Completion
  - `ClovaApiClient`
  - `RestClient.post().uri("/v1/chat-completions/...")` 또는 `"/v3/chat-completions/..."`

- Clova Embedding
  - `ClovaEmbeddingClient`
  - `RestClient.post().uri("/v1/api-tools/embedding/v2")`

- Firebase Messaging
  - `PushClientApplication`
  - `FirebaseMessaging.getInstance().send(...)`

이들은 HaruUp 백엔드 자체 API가 아니라 외부 서비스 호출입니다.
