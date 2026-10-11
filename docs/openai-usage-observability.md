# OpenAI 기능별 사용량·원가 측정 — 1단계

기존 서버 API 키, 요청 본문, 모델, 프롬프트, 검증 기준, 재시도 상한을 유지한다.
이번 변경은 측정 정보를 기존 서버 로그에 추가한다. 별도 DB 테이블이나 외부 수집 API는 추가하지 않는다.
운영 배포와 실제 사용량 수집은 별도 확인이 필요하며, 로컬 검증은 모의 응답만 사용한다.

## 기록과 집계 기준

`com.haruUp.global.openai.OpenAiUsageTracking`이 `OPENAI_TELEMETRY {JSON}` 한 줄을 기록한다.
기존 로그 설정을 사용한다. Railway의 `prod` 환경은 콘솔 로그만 사용하고,
그 밖의 환경은 콘솔/Loki 설정을 사용하므로 로그 보존 기간 내에 필요한 기간의 로그를 확보한다.

- `api_call`: API 시도당 한 건. 실패나 텍스트 없는 응답도 기록한다.
- `validation`: 기존 검증 결과(`passed`, `rejected`, `fallback`, `failed`, 목표 분석의 `parsed`).
- `operationId`: 한 질문·한 요약·한 미션 생성 작업. 자동 재시도는 동일 ID, `attempt`는 1부터 증가.
- `sessionId`: 큐레이션 세션 ID. 여러 HTTP 요청의 질문·요약·최초 미션 비용을 합산할 수 있다.
- `environment`: Spring 활성 프로파일. 직접 생성하는 평가 클라이언트는 `evaluation`.
- `promptVersion`: 현재 프롬프트 기준 `baseline-v1`. 이후 프롬프트 변경 시 해당 operation의 버전을 갱신한다.
- `responseId`, 실제 응답 모델, 서비스 티어, 응답 상태, 호출 결과, 소요 시간.
- 입력·캐시 입력·출력·추론·전체 토큰과 표준 단가 기반 추정 비용.

측정 로그에는 사용자 대화, 프롬프트, 생성 원문, API 키, HTTP 오류 본문을 넣지 않는다.
기존 서비스가 남기던 일반 로그의 내용은 이번 변경 범위 밖이다.
`text_returned`는 텍스트 반환 성공이며 검증 통과 또는 DB 저장 완료를 의미하지 않는다.
`responseStatus`는 OpenAI가 보고한 상태이므로 둘을 함께 확인한다.

## 기능 구분

| feature | 용도 |
| --- | --- |
| `goal_analysis` | 목표 단일성·시간 투자 필요 분석 |
| `curation_question` | 꼬리질문 및 충분 여부 판정 |
| `curation_summary` | 상세·짧은 요약 생성 |
| `mission_initial` | 큐레이션 완료 직후 최초 미션 생성 |
| `mission_retry` | 사용자 목표 미션 재추천 |
| `mission_generation` | 그 밖의 목표 미션 서비스 직접 호출 |
| `evaluation_simulated_user` | 평가 하네스의 가상 사용자 답변, 서비스 원가에서 제외 |
| `unclassified` | 아직 기능별 scope를 지정하지 않은 기타 Responses 호출 |

현재 목표 미션 서비스의 직접 호출부는 최초 생성과 재추천이다.
일일 배치 호출을 연결할 때에는 `mission_daily` scope를 명시해 별도로 구분한다.
현재 `mission_generation`을 일일 생성으로 추정해서 집계하지 않는다.
임베딩은 별도 클라이언트이며 이 Responses 측정 범위에 포함되지 않는다.

## 비용의 정확성과 한계

[OpenAI GPT-5.4 Mini 가격](https://developers.openai.com/api/docs/models/gpt-5.4-mini)을 2026-10-10 기준으로 적용한다.
일반 입력 USD 0.75 / 캐시 입력 USD 0.075 / 출력 USD 4.50, 모두 100만 토큰당 가격이다.

```text
estimatedCostUsd = ((inputTokens - cachedInputTokens) × 0.75
                  + cachedInputTokens × 0.075
                  + outputTokens × 4.50) / 1,000,000
```

캐시 입력은 입력의 부분집합이고 추론은 출력의 부분집합이므로 다시 더하지 않는다.
사용량이 없는 호출은 `usageStatus=unknown`, 숫자는 null로 기록한다. 0으로 간주하지 않는다.
일반·캐시·출력 수치가 모두 있고, 알려진 GPT-5.4 Mini 모델이며, 응답 티어가 `default`일 때만 비용을 계산한다.
미확인 모델·티어, 불완전한 사용량, 비정상적인 캐시 수치는 `costStatus=unknown`으로 남긴다.
이 값은 표준 텍스트 단가 추정치이며 세금, 지역 처리 추가 요금, 툴 요금 등은 반영하지 않는다.
가격 변경 시 계산기와 `pricingVersion`을 함께 변경한다. 최종 프로젝트 비용은 Usage 대시보드와 대조한다.

요청당 평균을 목표/사용자당 원가로 바꾸지 않는다. 작업별 모든 재시도 비용을 합산한다.
캐시 입력 비중은 캐시 토큰 / 입력 토큰이며 요청 적중률과 다르다.
fallback 비율과 첫 시도 통과율은 `validation` 결과로 판단한다. `parsed` 목표 분석은 품질 검증 통과로 세지 않는다.
scope는 현재 동기식 RestClient 경로에 한정되며, 비동기/코루틴으로 이동하면 컨텍스트 전달 방식도 변경해야 한다.

## 로그 집계

필요한 기간의 **원본 콘솔 로그**를 로컬 파일로 확보한 후 실행한다. 로그에는 API 키를 넣지 않는다.
Loki JSON envelope를 그대로 넘기는 형식은 지원하지 않으므로 원본 message 텍스트를 먼저 추출한다.

```bash
python3 scripts/summarize_openai_usage.py /path/to/server.log > /tmp/openai-usage-summary.json
```

결과는 환경·기능별 호출 수, 작업 수, 자동 재시도 수, 검증 실패 유형, 알려진 비용 합계 및 세션별 합계다.
작업에 비용 미확인 호출이 하나라도 있으면 평균 작업 원가를 null로 표시한다.
토큰 합계는 보고된 수치만의 합계이며 `unknownUsageCalls`, `unknownCachedInputCalls`를 함께 확인한다.
`malformedTelemetryLines`가 0이 아니면 로그 형식이나 잘림을 확인한 뒤 집계한다.
중복으로 수집된 로그를 합산하지 않고, 같은 기간·프로젝트·운영 환경만 Usage와 비교한다.
세션 합계는 수집 파일에 포함된 호출의 합계다. 파일이 세션 중간에서 시작/종료되면 완료 세션 원가로 해석하지 않는다.
평가 환경 세션 합계에는 가상 사용자 호출도 포함되므로 서비스 원가 비교에는 해당 feature를 제외한 그룹을 사용한다.

## 로컬 검증

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew unitTest \
  --tests 'com.haruUp.global.openai.*' \
  --tests 'com.haruUp.curation.application.*' \
  --tests 'com.haruUp.mission.application.*' --offline
```

모의 API 검증 범위: 두 호출 경로의 usage 파싱, 캐시·추론 중복 과금 방지,
사용량 누락/명시적 0/미확인 단가, HTTP 실패, 텍스트 없는 유료 응답,
스코프 복구, 실제 미션 서비스의 검증 실패→재시도→성공 연결.
`curationEval`은 실제 API 비용이 발생하므로 이번 모의 검증에서는 실행하지 않는다.

2026-10-11 배포 준비 검증: `./gradlew unitTest --offline`으로 전체 단위 테스트
128개가 통과했다. 서버 키와 배포 환경변수의 변경은 필요하지 않다.

## 다음 확인

1. 서버 반영 후 기존 키로 발생한 운영 로그에서 feature·usage·attempt 기록을 확인한다.
2. 완료 큐레이션 및 미션 작업 표본을 수집하고 Usage와 기간·환경을 맞춰 대조한다.
3. 미확인 비용 호출 및 unclassified 호출 비중을 확인한다.
4. 재시도 사유와 작업당 원가를 기준선으로 확정한 뒤 2단계 미션 재생성 개선에 착수한다.

운영 수집 확인 전에는 절감률 또는 실제 사용자 원가가 검증됐다고 보고하지 않는다.
