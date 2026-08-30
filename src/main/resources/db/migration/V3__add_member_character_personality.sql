-- 큐레이션 꼬리질문 말투에 반영되는 AI 성격
--
-- WARM_FRIEND : 따뜻하게 응원하며 함께 가는 친구
-- CLEAR_COACH : 명확한 계획으로 이끌어주는 코치
--
-- NULL 이면 WARM_FRIEND 가 기본값으로 적용된다.
-- check 제약은 V1 이 새 DB 에 만드는 것과 동일한 형태로 맞춘다.

ALTER TABLE member_character
    ADD COLUMN personality varchar(255)
    CHECK (personality IN ('WARM_FRIEND', 'CLEAR_COACH'));
