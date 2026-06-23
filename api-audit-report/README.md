# HaruUp API Audit Report

작성일: 2026-06-06

## 확인 범위

- Backend: `/Users/joyeonghyeon/Documents/Develop/HaruUp Project/haru-up-back-end-2`
- iOS Frontend: `/Users/joyeonghyeon/Documents/Develop/HaruUp Project/haru-up-front-end`
- Backend 기준 활성 API: 57개
- iOS 적용 판단 기준: `NetworkDefine`, `Service`, 직접 `AF.request`, ViewModel/Coordinator 호출 경로

## 요약

- iOS 적용 확인: 29개
- iOS 정의만 확인: 2개
- iOS 미적용: 26개

`iOS 미적용`은 "삭제 가능"과 같은 뜻이 아닙니다. 백엔드 내부 배치, 관리자/운영용 API, 향후 기능 API일 수 있으므로 목적별로 분리했습니다.

## 파일 구성

- [api-application-matrix.md](./api-application-matrix.md): 전체 백엔드 API와 iOS 적용 상태
- [action-items.md](./action-items.md): 적용 필요 후보, 불필요/정리 후보, 삭제 전 확인사항
- [backend-internal-usage.md](./backend-internal-usage.md): 백엔드 내부에서 API endpoint가 아니라 서비스로 재사용되는 기능

## 상태 표기

- `iOS 적용`: iOS에서 실제 요청 함수 또는 화면 호출 경로가 확인됨
- `iOS 정의만`: iOS에 URL/서비스 정의는 있으나 화면 호출 경로가 확인되지 않음
- `iOS 미적용`: iOS에 endpoint 정의와 호출이 확인되지 않음
- `유지 권장`: iOS 호출 여부와 별개로 운영/배치/인프라 목적이 있거나 삭제 위험이 큼
- `적용 검토`: iOS 기능상 연결하면 좋은 API
- `정리 후보`: 중복, 레거시, 테스트/운영 노출 위험 등으로 정리 검토 필요
