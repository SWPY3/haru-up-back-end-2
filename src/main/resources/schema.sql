-- 애플리케이션 시작 시 자동 실행되는 스키마 보정 스크립트
--
-- ddl-auto: none 이라 Hibernate가 컬럼을 만들어 주지 않는다.
-- 엔티티에 컬럼을 추가할 때마다 사람이 DDL을 따로 실행해야 했고,
-- 실행을 빠뜨리면 배포 후 저장이 실패했다. 그 수동 단계를 없애기 위한 파일이다.
--
-- 【작성 규칙】
-- 1. 반드시 IF NOT EXISTS / IF EXISTS 를 붙여 여러 번 실행해도 안전하게 만든다.
--    이 파일은 앱이 뜰 때마다 매번 실행된다.
-- 2. 컬럼 추가는 NULL 허용으로만 한다.
--    NOT NULL 컬럼을 추가하면 기존 행 때문에 실패하고, 구버전 코드와도 호환되지 않는다.
-- 3. 데이터를 지우거나 바꾸는 문장(DROP, DELETE, UPDATE)은 넣지 않는다.
--    되돌릴 수 없는 변경은 사람이 직접 판단해서 실행해야 한다.
-- 4. 테이블 생성은 여기서 하지 않는다. 최초 스키마는 별도로 관리한다.
--    테이블이 아직 없는 빈 DB에서도 기동이 깨지지 않도록 ALTER TABLE IF EXISTS 를 쓴다.

-- 큐레이션 대화 요약 분리 (2026-08)
-- conversation_summary 는 미션 생성 프롬프트용 상세 요약,
-- user_summary 는 사용자 화면에 그대로 보여주는 짧은 요약이다.
ALTER TABLE IF EXISTS member_goal
    ADD COLUMN IF NOT EXISTS user_summary TEXT NULL;

-- AI 성격 선택 (2026-08)
-- 값: WARM_FRIEND(따뜻하게 응원하며 함께 가는 친구) / CLEAR_COACH(명확한 계획으로 이끌어주는 코치)
-- NULL 이면 WARM_FRIEND 가 기본값으로 적용된다.
ALTER TABLE IF EXISTS member_character
    ADD COLUMN IF NOT EXISTS personality VARCHAR(30) NULL;
