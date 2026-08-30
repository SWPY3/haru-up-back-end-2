-- 큐레이션 꼬리질문 말투에 반영되는 AI 성격 선택값
--
-- 값: WARM_FRIEND(따뜻하게 응원하며 함께 가는 친구) / CLEAR_COACH(명확한 계획으로 이끌어주는 코치)
--
-- ddl-auto: none 이므로 배포 전에 직접 실행해야 한다.
-- 아직 성격을 고르지 않은 회원은 NULL이며, 이 경우 WARM_FRIEND가 기본값으로 적용된다.

ALTER TABLE member_character
    ADD COLUMN personality VARCHAR(30) NULL;
