package com.haruUp.mission.application

import com.haruUp.global.util.KoreanGrammarChecker

/**
 * OpenAI 응답에서 파싱한 미션 1개.
 *
 * 저장 엔티티가 아니라 검증을 거치기 전의 중간 표현이다.
 */
data class ParsedMission(
    val content: String,
    val description: String,
    val difficulty: Int
)

/**
 * 생성된 미션이 프롬프트(DailyMissionFromGoalPrompt)의 기준을 지켰는지 검증한다.
 *
 * 프롬프트에만 적은 규칙은 새어 나간다는 것이 평가(curationEval)에서 반복 확인됐다.
 * 사용자에게 그대로 노출되는 값이므로 코드로도 막고, 어긋나면 재생성시킨다.
 *
 * 아래 기준값은 모두 프롬프트의 기준과 **반드시 일치해야 한다.**
 * 한쪽만 바꾸면 모델이 지킬 수 없는 기준으로 재시도만 반복하게 된다.
 */
object GeneratedMissionValidator {

    /** 난이도(하/중/상)별로 생성할 미션 수 */
    const val MISSIONS_PER_DIFFICULTY = 5

    /**
     * content(미션 제목) 글자수.
     *
     * 제목은 목록 화면에서 한 줄로 보여주므로 너무 길면 잘리고, 너무 짧으면 무엇을 할지 알 수 없다.
     */
    const val MIN_CONTENT_LENGTH = 10
    const val MAX_CONTENT_LENGTH = 25

    /** description(실행 방법) 글자수. UI 표시 폭이 넓어지면 이 값과 프롬프트의 기준을 함께 올리면 된다. */
    const val MIN_DESCRIPTION_LENGTH = 20
    const val MAX_DESCRIPTION_LENGTH = 30

    /**
     * 검증 결과. 재생성 여부 판단과 위반 로그에 모두 사용한다.
     *
     * 통과 여부만이 아니라 어긋난 미션 목록을 그대로 담는다.
     * 재생성 로그에 "무엇이 몇 자여서 걸렸는지"를 남겨야 프롬프트를 고칠 근거가 되기 때문이다.
     */
    data class Result(
        val shortContents: List<ParsedMission> = emptyList(),
        val longContents: List<ParsedMission> = emptyList(),
        val shortDescriptions: List<ParsedMission> = emptyList(),
        val longDescriptions: List<ParsedMission> = emptyList(),
        val grammarViolations: List<String> = emptyList(),
        val duplicated: List<ParsedMission> = emptyList()
    ) {
        val passed: Boolean
            get() = shortContents.isEmpty() && longContents.isEmpty() &&
                shortDescriptions.isEmpty() && longDescriptions.isEmpty() &&
                grammarViolations.isEmpty() && duplicated.isEmpty()
    }

    /**
     * 난이도 분포가 하/중/상 각각 [MISSIONS_PER_DIFFICULTY]개인지 검증한다.
     *
     * 분포가 어긋나면 결과를 쓸 수 없으므로 [validate]와 분리해 먼저 확인한다.
     * (글자수·문법 위반은 마지막 재시도까지 실패하면 그대로라도 쓰지만, 분포 실패는 예외로 이어진다)
     */
    fun hasValidDifficultyDistribution(missions: List<ParsedMission>): Boolean {
        val grouped = missions.groupBy { it.difficulty }
        return grouped[1]?.size == MISSIONS_PER_DIFFICULTY &&
            grouped[2]?.size == MISSIONS_PER_DIFFICULTY &&
            grouped[3]?.size == MISSIONS_PER_DIFFICULTY
    }

    /**
     * 글자수·한국어 문법·중복을 검증한다.
     *
     * @param pastMissions 이미 제공한 미션 제목. 프롬프트로 "반복 금지"를 지시해도
     *                     모델이 같은 미션을 다시 내는 경우가 있어 코드로도 막는다.
     */
    fun validate(missions: List<ParsedMission>, pastMissions: Set<String>): Result {
        return Result(
            shortContents = missions.filter { it.content.length < MIN_CONTENT_LENGTH },
            longContents = missions.filter { it.content.length > MAX_CONTENT_LENGTH },
            shortDescriptions = missions.filter { it.description.length < MIN_DESCRIPTION_LENGTH },
            longDescriptions = missions.filter { it.description.length > MAX_DESCRIPTION_LENGTH },
            grammarViolations = findGrammarViolations(missions),
            duplicated = missions.filter { it.content in pastMissions }
        )
    }

    /**
     * 글자수 기준을 벗어나거나 이미 제공한 미션과 중복되는 건수를 센다.
     * 재시도가 모두 실패했을 때 가장 덜 어긋난 결과를 고르는 데 사용한다.
     *
     * 한 미션이 여러 기준을 동시에 어겨도 1건으로 센다. 결과끼리 위반 정도를 비교하는 용도라
     * 어긋난 미션이 몇 개인지가 기준이다.
     */
    fun countViolations(missions: List<ParsedMission>, pastMissions: Set<String>): Int {
        return missions.count {
            it.content.length !in MIN_CONTENT_LENGTH..MAX_CONTENT_LENGTH ||
                it.description.length !in MIN_DESCRIPTION_LENGTH..MAX_DESCRIPTION_LENGTH ||
                it.content in pastMissions
        }
    }

    /**
     * 미션 제목/설명에서 명백한 한국어 문법 오류를 찾아 사유 목록으로 반환한다.
     * 사용자에게 그대로 노출되는 문장이므로 오류가 있으면 재생성한다.
     */
    private fun findGrammarViolations(missions: List<ParsedMission>): List<String> {
        return missions.flatMap { mission ->
            listOfNotNull(
                KoreanGrammarChecker.findViolation(mission.content)
                    ?.let { "content \"${mission.content}\" - $it" },
                KoreanGrammarChecker.findViolation(mission.description)
                    ?.let { "description \"${mission.description}\" - $it" }
            )
        }
    }
}
