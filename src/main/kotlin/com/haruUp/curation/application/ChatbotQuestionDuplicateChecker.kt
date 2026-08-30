package com.haruUp.curation.application

/**
 * 이미 물어본 질문과 같은 정보를 다시 묻는 꼬리질문을 코드 레벨로 걸러낸다.
 *
 * 프롬프트에도 "의미 중복 금지"를 지시하지만 지시만으로는 새어 나오기 때문에
 * (평가에서 "목표로 하는 감량 폭이 몇 kg인가요?" 뒤에 "목표 체중을 몇 kg으로 잡으셨나요?"가 생성됨)
 * 확실한 중복만 탐지해 재생성 트리거로 사용한다.
 *
 * 중복 판정은 곧 재생성(추가 API 호출)이므로 오탐 비용이 크다.
 * "중복을 놓치는 것"보다 "정상 질문을 막는 것"이 더 나쁘다고 보고 규칙을 좁게 잡았다.
 * 그래서 같은 영역이라는 이유만으로는 막지 않고, 아래 세 가지 확실한 신호만 본다.
 */
object ChatbotQuestionDuplicateChecker {

    /** 어휘가 이 비율 이상 겹치면 사실상 같은 질문을 다시 쓴 것으로 본다. */
    private const val LEXICAL_OVERLAP_THRESHOLD = 0.6

    /**
     * 세션에서 한 번만 물어야 하는 주제.
     * 표현을 바꿔도 같은 정보를 얻게 되는 주제만 넣는다.
     */
    private val SINGLE_USE_TOPICS = listOf(
        "동기" to Regex("이유|왜|동기"),
        "가용 시간" to Regex("시간|몇\\s*분|몇\\s*시|언제|기한")
    )

    /** "몇 ○○" 형태로 묻는 수량 단위. 긴 단위를 먼저 검사한다. */
    private val QUANTITY_UNIT_REGEX =
        Regex("(?:몇|얼마)\\s*(개비|만원|페이지|시간|칼로리|kg|km|점|분|개|번|회|원|권|쪽|잔)")

    /** 수치 질문이 '목표치'를 묻는지 판별 */
    private val TARGET_QUALIFIER_REGEX = Regex("목표|도달|잡으셨|되고\\s*싶|만들고\\s*싶")

    /** 수치 질문이 '현재치'를 묻는지 판별 */
    private val CURRENT_QUALIFIER_REGEX = Regex("지금|현재|요즘")

    /**
     * 질문 뼈대에만 쓰여 의미 비교에 도움이 되지 않는 어휘.
     *
     * '지금·현재·목표'는 뼈대처럼 보이지만 현재치와 목표치를 가르는 유일한 신호이므로
     * 불용어로 넣지 않는다. 넣으면 "지금 체중"과 "목표 체중"이 같은 질문으로 판정된다.
     */
    private val STOPWORDS = setOf(
        "가장", "무엇", "어떤", "어느", "정도", "얼마", "몇", "그", "이", "저", "것", "수", "때", "중",
        "하루", "제일", "주로", "혹시"
    )

    /** 토큰 끝에 붙는 조사·어미. 어휘 비교용으로만 떼어낸다. */
    private val TRAILING_PARTICLE_REGEX =
        Regex("(입니까|인가요|이신가요|하시나요|하세요|나요|까요|은요|는요|으로|로|에서|에게|에|이|가|은|는|을|를|와|과|의|도|만)$")

    private val NON_WORD_REGEX = Regex("[^가-힣a-zA-Z0-9\\s]")

    /**
     * 새로 만든 질문이 이미 물어본 질문과 중복인지 판단한다.
     *
     * @param question 새로 생성된 질문
     * @param previousQuestions 이미 사용자에게 나간 질문 목록
     * @return 중복 사유, 중복이 아니면 null
     */
    fun findDuplicate(question: String, previousQuestions: List<String>): String? {
        if (previousQuestions.isEmpty()) return null

        SINGLE_USE_TOPICS.forEach { (topicName, pattern) ->
            if (pattern.containsMatchIn(question)) {
                previousQuestions.find { pattern.containsMatchIn(it) }?.let { previous ->
                    return "이미 '$topicName'을(를) 물었습니다. (이전 질문: \"$previous\") 아직 묻지 않은 정보를 물으세요."
                }
            }
        }

        measuredTarget(question)?.let { current ->
            previousQuestions.find { measuredTarget(it) == current }?.let { previous ->
                return "이미 같은 값을 물었습니다. (이전 질문: \"$previous\") 다른 정보를 물으세요."
            }
        }

        val tokens = contentTokens(question)
        if (tokens.isNotEmpty()) {
            previousQuestions
                // 한쪽은 목표치, 다른 쪽은 현재치를 묻는다면 어휘가 겹쳐도 서로 다른 정보다.
                .filterNot { measuresDifferentValue(question, it) }
                .find { overlapRatio(tokens, contentTokens(it)) >= LEXICAL_OVERLAP_THRESHOLD }
                ?.let { previous ->
                    return "이전 질문과 표현만 다른 같은 질문입니다. (이전 질문: \"$previous\")"
                }
        }

        return null
    }

    /**
     * 질문이 묻는 수치를 "목표치/현재치 + 단위"로 요약한다.
     *
     * 목표 체중과 현재 체중처럼 단위가 같아도 성격이 다르면 서로 다른 정보이므로,
     * 단위만으로 판단하지 않고 목표/현재 구분을 함께 본다.
     * 목표인지 현재인지 알 수 없으면 판단을 포기한다. (오탐 방지)
     */
    private fun measuredTarget(question: String): String? {
        val unit = QUANTITY_UNIT_REGEX.find(question)?.groupValues?.get(1) ?: return null
        val qualifier = when {
            TARGET_QUALIFIER_REGEX.containsMatchIn(question) -> "목표"
            CURRENT_QUALIFIER_REGEX.containsMatchIn(question) -> "현재"
            else -> return null
        }
        return "$qualifier/$unit"
    }

    /**
     * 두 질문이 각각 목표치와 현재치처럼 서로 다른 성격의 값을 묻는지 판단한다.
     * 둘 중 하나라도 성격을 알 수 없으면 false를 반환해 어휘 비교를 그대로 진행한다.
     */
    private fun measuresDifferentValue(question: String, other: String): Boolean {
        val a = measuredTarget(question) ?: return false
        val b = measuredTarget(other) ?: return false
        return a != b
    }

    /** 조사·어미와 뼈대 어휘를 걷어낸 내용어 집합 */
    private fun contentTokens(question: String): Set<String> {
        return question
            .replace(NON_WORD_REGEX, " ")
            .split(Regex("\\s+"))
            .map { it.replace(TRAILING_PARTICLE_REGEX, "") }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .toSet()
    }

    /** 두 집합의 자카드 유사도 */
    private fun overlapRatio(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val union = a.size + b.size - a.count { it in b }
        return a.count { it in b }.toDouble() / union
    }
}
