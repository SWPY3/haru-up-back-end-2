-- 큐레이션 대화 요약을 용도별로 분리
--
-- conversation_summary : 미션 생성 프롬프트용 상세 요약 (기존)
-- user_summary         : 사용자 화면에 그대로 보여주는 짧은 요약 (신규)
--
-- 이 컬럼 추가 전에 만들어진 목표는 NULL 이며, 조회 API 가 상세 요약으로 대체해 응답한다.

ALTER TABLE member_goal
    ADD COLUMN user_summary TEXT;
