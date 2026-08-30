-- 큐레이션 대화 요약을 용도별로 분리하면서 추가된 컬럼
--
-- conversation_summary : 미션 생성 프롬프트에 넣는 상세 요약 (기존 컬럼, 사용자에게 노출하지 않음)
-- user_summary         : 사용자 화면에 그대로 보여주는 짧은 요약 (신규)
--
-- ddl-auto: none 이므로 배포 전에 직접 실행해야 한다.
-- 이 컬럼 추가 전에 만들어진 목표는 user_summary가 NULL이며,
-- 목표 조회 API는 NULL이면 conversation_summary로 대체해 응답한다.

ALTER TABLE member_goal
    ADD COLUMN user_summary TEXT NULL;
