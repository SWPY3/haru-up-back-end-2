package com.haruUp.mission.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.category.repository.JobDetailRepository
import com.haruUp.category.repository.JobJpaRepository
import com.haruUp.global.clova.MissionMemberProfile
import com.haruUp.interest.repository.InterestEmbeddingJpaRepository
import com.haruUp.interest.repository.MemberInterestJpaRepository
import com.haruUp.member.infrastructure.MemberProfileRepository
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MissionCandidateDto
import com.haruUp.mission.domain.MissionExpCalculator
import com.haruUp.mission.domain.MissionRecommendResult
import com.haruUp.mission.domain.MissionStatus
import com.haruUp.mission.infrastructure.MemberMissionRepository
import com.haruUp.missionembedding.dto.MissionRecommendationResponse
import com.haruUp.missionembedding.service.MissionRecommendationService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period

@Service
class MissionRecommendService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val missionRecommendationService: MissionRecommendationService,
    private val memberProfileRepository: MemberProfileRepository,
    private val memberMissionRepository: MemberMissionRepository,
    private val jobRepository: JobJpaRepository,
    private val jobDetailRepository: JobDetailRepository,
    private val memberInterestRepository: MemberInterestJpaRepository,
    private val interestEmbeddingRepository: InterestEmbeddingJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val TODAY_RETRY_TTL = Duration.ofHours(24)  // 24시간 후 자동 만료

    /**
     * 미션 추천 조회
     *
     * member_mission에서 조건에 맞는 미션 조회:
     * - deleted = false
     * - targetDate = 지정된 날짜
     * - missionStatus IN (전달받은 statuses)
     *
     * @param memberId 멤버 ID
     * @param memberInterestId 멤버 관심사 ID
     * @param targetDate 조회할 날짜
     * @param statuses 조회할 미션 상태 목록 (기본값: READY)
     */
    fun recommend(
        memberId: Long,
        memberInterestId: Long,
        targetDate: LocalDate,
        statuses: List<MissionStatus> = listOf(MissionStatus.READY)
    ): MissionRecommendResult {
        // member_interest에서 directFullPath 조회
        val memberInterest = memberInterestRepository.findById(memberInterestId).orElse(null)
        val directFullPath = memberInterest?.directFullPath ?: emptyList()

        // member_mission에서 미션 조회
        val memberMissions = memberMissionRepository.findTodayMissions(
            memberId = memberId,
            memberInterestId = memberInterestId,
            targetDate = targetDate,
            statuses = statuses
        )

        logger.info("미션 조회 - memberId: $memberId, memberInterestId: $memberInterestId, targetDate: $targetDate, 결과: ${memberMissions.size}개")

        // member_mission에서 직접 데이터를 조회하여 MissionCandidateDto로 변환
        val missions = memberMissions.map { memberMission ->
            MissionCandidateDto(
                memberMissionId = memberMission.id!!,
                missionStatus = memberMission.missionStatus,
                content = memberMission.missionContent,
                directFullPath = directFullPath,
                difficulty = memberMission.difficulty,
                expEarned = memberMission.expEarned,
                targetDate = memberMission.targetDate,
                missionDescription = memberMission.missionDescription
            )
        }

        return MissionRecommendResult(
            missions = missions,
            retryCount = getRetryCount(memberId)
        )
    }

    /**
     * 재추천 (memberInterestId 기반)
     *
     * 사용자 프로필과 관심사 정보를 기반으로 미션 재추천
     * - excludeMemberMissionIds에 해당하는 미션의 난이도는 제외하고 추천
     * - 제외된 미션은 soft delete 하지 않고 유지
     * - 재추천된 미션만 READY 상태로 저장
     * - 추천된 미션 ID는 Redis에 캐싱 (24시간)
     * - reset_mission_count 카운트 증가
     *
     * @param excludeMemberMissionIds 제외할 member_mission ID 목록 (해당 난이도는 재추천에서 제외)
     */
    suspend fun retryWithInterest(
        memberId: Long,
        memberInterestId: Long,
        excludeMemberMissionIds: List<Long>? = null
    ): MissionRecommendationResponse {
        if (getRetryCount(memberId) >= 5) {
            throw IllegalArgumentException("재추천 횟수 초과: 최대 5회까지 가능합니다.")
        }

        // 1. DB에서 사용자 프로필 조회
        val memberProfileEntity = memberProfileRepository.findByMemberId(memberId)
            ?: throw IllegalArgumentException("사용자 프로필을 찾을 수 없습니다.")

        // 2. 직업 정보 조회
        val jobName = memberProfileEntity.jobId?.let { jobId ->
            jobRepository.findById(jobId).orElse(null)?.jobName
        }
        val jobDetailName = memberProfileEntity.jobDetailId?.let { jobDetailId ->
            jobDetailRepository.findById(jobDetailId).orElse(null)?.jobDetailName
        }

        val missionMemberProfile = MissionMemberProfile(
            age = memberProfileEntity.birthDt?.let { calculateAge(it) },
            gender = memberProfileEntity.gender?.name,
            jobName = jobName,
            jobDetailName = jobDetailName
        )

        // 3. 멤버 관심사 조회
        val memberInterest = memberInterestRepository.findById(memberInterestId).orElse(null)
            ?: throw IllegalArgumentException("멤버 관심사를 찾을 수 없습니다. (memberInterestId: $memberInterestId)")

        // 3-1. 해당 관심사가 현재 사용자의 것인지 확인
        if (memberInterest.memberId != memberId) {
            throw IllegalArgumentException("해당 관심사에 접근 권한이 없습니다.")
        }

        val directFullPath = memberInterest.directFullPath
            ?: throw IllegalArgumentException("관심사 경로 정보가 없습니다. (memberInterestId: $memberInterestId)")

        // 4. READY 상태 미션 조회 및 제외할 미션 유효성 검증
        val readyMissions = memberMissionRepository.findByMemberIdAndMemberInterestIdAndMissionStatusAndDeletedFalse(
            memberId = memberId,
            memberInterestId = memberInterestId,
            missionStatus = MissionStatus.READY
        )
        val readyDifficulties = readyMissions.mapNotNull { it.difficulty }.distinct()
        logger.info("READY 상태 미션 난이도: $readyDifficulties")

        val excludeDifficulties = if (!excludeMemberMissionIds.isNullOrEmpty()) {
            // 유효성 검증: READY 상태인 미션만 제외(유지) 가능
            val validMemberMissionIds = readyMissions.map { it.id }
            val invalidIds = excludeMemberMissionIds.filter { it !in validMemberMissionIds }
            if (invalidIds.isNotEmpty()) {
                throw IllegalArgumentException("제외할 미션이 유효하지 않습니다. (invalidIds: $invalidIds)")
            }

            // 제외(유지)할 미션의 난이도 조회
            val excludedMemberMissions = memberMissionRepository.findAllById(excludeMemberMissionIds)
            val difficulties = excludedMemberMissions
                .mapNotNull { it.difficulty }
                .distinct()
            logger.info("제외(유지)할 난이도: $difficulties (member_mission_ids: $excludeMemberMissionIds)")
            difficulties
        } else {
            emptyList()
        }

        // 5. 추천할 난이도 결정 (전체 난이도 1~5 중 제외 난이도를 뺀 나머지)
        val allDifficulties = listOf(1, 2, 3, 4, 5)
        val targetDifficulties = allDifficulties.filter { it !in excludeDifficulties }
        logger.info("추천할 난이도: $targetDifficulties (제외: $excludeDifficulties)")

        if (targetDifficulties.isEmpty()) {
            logger.info("추천할 난이도가 없습니다. 빈 응답 반환")
            return MissionRecommendationResponse(
                missions = listOf(
                    com.haruUp.missionembedding.dto.MissionGroupDto(
                        memberInterestId = memberInterestId.toInt(),
                        data = emptyList()
                    )
                ),
                totalCount = 0,
                retryCount = getRetryCount(memberId)
            )
        }

        // 6. 제외할 미션 내용 수집 (ACTIVE 상태 미션의 내용)
        val activeMissionContents = memberMissionRepository.findMissionContentsByMemberIdAndStatus(
            memberId = memberId,
            status = MissionStatus.ACTIVE
        )
        logger.info("ACTIVE 상태 미션 내용: ${activeMissionContents.size}개")

        // 현재 관심사의 기존 미션 내용도 제외
        val existingMissionContents = memberMissionRepository
            .findByMemberIdAndMemberInterestIdAndDeletedFalse(memberId, memberInterestId)
            .map { it.missionContent }

        // Redis 캐시에서 오늘 추천된 미션 내용 조회 (soft delete된 미션도 제외하기 위함)
        val cachedMissionContents = getRecommendedMissionContents(memberId, memberInterestId)
        logger.info("Redis 캐시 미션 내용: ${cachedMissionContents.size}개")

        val excludeContents = (activeMissionContents + existingMissionContents + cachedMissionContents).distinct()
        logger.info("제외할 미션 내용 총합: ${excludeContents.size}개")

        // 7. 미션 추천 (targetDifficulties에 해당하는 난이도만)
        val missionDtos = missionRecommendationService.recommendTodayMissions(
            directFullPath = directFullPath,
            memberProfile = missionMemberProfile,
            difficulties = targetDifficulties,
            excludeContents = excludeContents
        )

        // 8. 제외된 미션을 제외한 기존 READY 상태 member_mission soft delete
        val excludeMemberMissionIdSet = excludeMemberMissionIds?.toSet() ?: emptySet()
        if (excludeMemberMissionIdSet.isEmpty()) {
            // 제외할 미션이 없으면 기존처럼 전체 READY 상태 soft delete
            val deletedCount = memberMissionRepository.softDeleteByMemberIdAndInterestIdAndStatus(
                memberId = memberId,
                memberInterestId = memberInterestId,
                status = MissionStatus.READY,
                deletedAt = LocalDateTime.now()
            )
            logger.info("기존 READY 상태 미션 soft delete 완료 (memberInterestId: $memberInterestId): ${deletedCount}개")
        } else {
            // 제외할 미션을 제외하고 나머지 READY 상태만 soft delete
            val deletedCount = memberMissionRepository.softDeleteByMemberIdAndInterestIdAndStatusExcludingIds(
                memberId = memberId,
                memberInterestId = memberInterestId,
                status = MissionStatus.READY,
                excludeIds = excludeMemberMissionIdSet.toList(),
                deletedAt = LocalDateTime.now()
            )
            logger.info("기존 READY 상태 미션 soft delete 완료 (제외: $excludeMemberMissionIdSet): ${deletedCount}개")
        }

        // 9. 추천된 미션들을 member_mission에 READY 상태로 저장
        val savedMemberMissions = missionDtos.mapNotNull { missionDto ->
            try {
                val memberMission = MemberMissionEntity(
                    memberId = memberId,
                    memberInterestId = memberInterestId,
                    missionContent = missionDto.content,
                    difficulty = missionDto.difficulty,
                    missionStatus = MissionStatus.READY,
                    expEarned = MissionExpCalculator.calculateByDifficulty(missionDto.difficulty)
                )
                memberMissionRepository.save(memberMission)
            } catch (e: Exception) {
                logger.error("member_mission 저장 실패: content=${missionDto.content}, 에러: ${e.message}")
                null
            }
        }
        logger.info("member_mission READY 상태로 저장 완료: ${savedMemberMissions.size}개")

        // 10. 추천된 미션 내용을 Redis에 저장 (다음 재추천 시 제외하기 위함)
        val recommendedContents = savedMemberMissions.map { it.missionContent }
        if (recommendedContents.isNotEmpty()) {
            saveRecommendedMissionContents(memberId, memberInterestId, recommendedContents)
        }

        // 11. reset_mission_count 증가
        memberInterest.incrementResetMissionCount()
        memberInterestRepository.save(memberInterest)
        logger.info("reset_mission_count 증가: ${memberInterest.resetMissionCount}")

        // 12. 응답 생성 - 저장된 member_mission 정보를 사용
        val responseMissionDtos = savedMemberMissions.map { memberMission ->
            com.haruUp.missionembedding.dto.MissionDto(
                member_mission_id = memberMission.id,
                content = memberMission.missionContent,
                directFullPath = directFullPath,
                difficulty = memberMission.difficulty,
                expEarned = memberMission.expEarned,
                createdType = "AI"
            )
        }

        val missionGroup = com.haruUp.missionembedding.dto.MissionGroupDto(
            memberInterestId = memberInterestId.toInt(),
            data = responseMissionDtos
        )

        logger.info("오늘의 미션 재추천 성공: ${responseMissionDtos.size}개")

        return MissionRecommendationResponse(
            missions = listOf(missionGroup),
            totalCount = responseMissionDtos.size,
            retryCount = incrementRetryCount(memberId)
        )
    }

    /**
     * 멤버 관심사 ID 목록 기반 미션 추천
     *
     * Controller와 Curation에서 공통으로 사용하는 미션 추천 로직
     * - 멤버 관심사 유효성 검증
     * - 사용자 프로필 조회
     * - 미션 추천 (각 관심사당 난이도 1~5)
     * - 기존 READY 상태 미션 soft delete
     * - 추천된 미션 저장
     *
     * @param memberId 멤버 ID
     * @param memberInterestIds 멤버 관심사 ID 목록
     * @return 추천된 미션 응답
     */
    fun recommendByMemberInterestIds(
        memberId: Long,
        memberInterestIds: List<Long>
    ): MissionRecommendationResponse {
        // 1. memberInterestIds 유효성 검증 및 조회
        val memberInterests = memberInterestIds.mapNotNull { memberInterestId ->
            val memberInterest = memberInterestRepository.findById(memberInterestId).orElse(null)
            if (memberInterest == null) {
                logger.warn("멤버 관심사를 찾을 수 없습니다: memberInterestId=$memberInterestId")
                return@mapNotNull null
            }
            if (memberInterest.memberId != memberId) {
                logger.warn("해당 관심사에 접근 권한이 없습니다: memberInterestId=$memberInterestId")
                return@mapNotNull null
            }
            memberInterest
        }

        require(memberInterests.isNotEmpty()) { "유효한 관심사가 없습니다." }

        // 2. DB에서 사용자 프로필 조회
        val memberProfileEntity = memberProfileRepository.findByMemberId(memberId)
            ?: throw IllegalArgumentException("사용자 프로필을 찾을 수 없습니다.")

        // 3. 직업 정보 조회
        val jobName = memberProfileEntity.jobId?.let { jobId ->
            jobRepository.findById(jobId).orElse(null)?.jobName
        }
        val jobDetailName = memberProfileEntity.jobDetailId?.let { jobDetailId ->
            jobDetailRepository.findById(jobDetailId).orElse(null)?.jobDetailName
        }

        val missionMemberProfile = MissionMemberProfile(
            age = memberProfileEntity.birthDt?.let { calculateAge(it) },
            gender = memberProfileEntity.gender?.name,
            jobName = jobName,
            jobDetailName = jobDetailName
        )

        logger.info("사용자 프로필 조회 완료 - 나이: ${missionMemberProfile.age}, 성별: ${missionMemberProfile.gender}, 직업: ${missionMemberProfile.jobName}")

        // 4. 각 관심사별로 미션 추천 (오늘 추천된 미션 제외)
        val today = LocalDate.now()
        val missions = mutableListOf<com.haruUp.missionembedding.dto.MissionGroupDto>()

        for (memberInterest in memberInterests) {
            val directFullPath = memberInterest.directFullPath
                ?: throw IllegalArgumentException("관심사 경로 정보가 없습니다: memberInterestId=${memberInterest.id}")

            // 오늘 이미 추천된 미션 내용 조회 (제외할 미션)
            val todayMemberMissions = memberMissionRepository.findByMemberIdAndMemberInterestIdAndTargetDate(
                memberId = memberId,
                memberInterestId = memberInterest.id!!,
                targetDate = today
            )
            val excludeContents = todayMemberMissions.map { it.missionContent }
            logger.info("오늘 추천된 미션 제외 - memberInterestId: ${memberInterest.id}, excludeContents: ${excludeContents.size}개")

            // 미션 추천 (제외할 미션 내용 전달)
            val missionDtos = kotlinx.coroutines.runBlocking {
                missionRecommendationService.recommendTodayMissions(
                    directFullPath = directFullPath,
                    memberProfile = missionMemberProfile,
                    excludeContents = excludeContents
                )
            }

            missions.add(
                com.haruUp.missionembedding.dto.MissionGroupDto(
                    memberInterestId = memberInterest.id!!.toInt(),
                    data = missionDtos
                )
            )
        }

        // 6. 요청받은 관심사들의 기존 READY 상태 member_mission soft delete
        var totalDeletedCount = 0
        for (memberInterest in memberInterests) {
            val deletedCount = memberMissionRepository.softDeleteByMemberIdAndInterestIdAndStatus(
                memberId = memberId,
                memberInterestId = memberInterest.id!!,
                status = MissionStatus.READY,
                deletedAt = LocalDateTime.now()
            )
            totalDeletedCount += deletedCount
        }
        logger.info("기존 READY 상태 미션 soft delete 완료: ${totalDeletedCount}개")

        // 7. 추천된 미션들을 member_mission에 READY 상태로 저장
        val savedMissionGroups = mutableListOf<com.haruUp.missionembedding.dto.MissionGroupDto>()
        var savedMissionCount = 0

        for (memberInterest in memberInterests) {
            val memberInterestId = memberInterest.id!!.toInt()
            val directFullPath = memberInterest.directFullPath ?: emptyList()

            val missionGroup = missions.find { it.memberInterestId == memberInterestId }
            if (missionGroup == null) {
                logger.warn("memberInterestId=${memberInterestId}에 해당하는 미션 그룹을 찾을 수 없습니다.")
                continue
            }

            val savedMissionDtos = mutableListOf<com.haruUp.missionembedding.dto.MissionDto>()
            for (missionDto in missionGroup.data) {
                try {
                    val memberMission = MemberMissionEntity(
                        memberId = memberId,
                        memberInterestId = memberInterest.id!!,
                        missionContent = missionDto.content,
                        difficulty = missionDto.difficulty,
                        missionStatus = MissionStatus.READY,
                        expEarned = MissionExpCalculator.calculateByDifficulty(missionDto.difficulty)
                    )
                    val saved = memberMissionRepository.save(memberMission)
                    savedMissionDtos.add(
                        com.haruUp.missionembedding.dto.MissionDto(
                            member_mission_id = saved.id,
                            content = saved.missionContent,
                            directFullPath = directFullPath,
                            difficulty = saved.difficulty,
                            expEarned = saved.expEarned,
                            createdType = "AI"
                        )
                    )
                    savedMissionCount++
                } catch (e: Exception) {
                    logger.error("member_mission 저장 실패: content=${missionDto.content}, 에러: ${e.message}")
                }
            }

            savedMissionGroups.add(
                com.haruUp.missionembedding.dto.MissionGroupDto(
                    memberInterestId = memberInterestId,
                    data = savedMissionDtos
                )
            )
        }
        logger.info("member_mission READY 상태로 저장 완료: ${savedMissionCount}개")

        return MissionRecommendationResponse(
            missions = savedMissionGroups,
            totalCount = savedMissionGroups.sumOf { it.data.size }
        )
    }

    /**
     * 생년월일로부터 나이 계산
     */
    fun calculateAge(birthDt: LocalDateTime): Int {
        val birthDate = birthDt.toLocalDate()
        val now = LocalDateTime.now().toLocalDate()
        return Period.between(birthDate, now).years
    }

    /** 현재 시각부터 다음 자정까지 남은 초를 계산한다. */
    fun secondsUntilMidnight(): Long {
        val now = LocalDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        return Duration.between(now, midnight).seconds
    }

    /**
     * 추천된 미션 내용 목록 저장
     *
     * @param memberId 사용자 ID
     * @param memberInterestId 멤버 관심사 ID
     * @param contents 추천된 미션 내용 목록
     */
    fun saveRecommendedMissionContents(memberId: Long, memberInterestId: Long, contents: List<String>) {
        val key = MissionRecommendRedisKey.recommendedContents(memberId, memberInterestId, LocalDate.now())
        try {
            // 기존 값에 추가
            val existingContents = getRecommendedMissionContents(memberId, memberInterestId)
            val allContents = (existingContents + contents).distinct()

            // Set으로 저장
            redisTemplate.delete(key)
            if (allContents.isNotEmpty()) {
                redisTemplate.opsForSet().add(key, *allContents.toTypedArray())
                redisTemplate.expire(key, TODAY_RETRY_TTL)
            }

            logger.info("추천 미션 내용 캐시 저장 - key: $key, count: ${allContents.size}")
        } catch (e: Exception) {
            logger.error("추천 미션 내용 캐시 저장 실패 - key: $key, error: ${e.message}")
        }
    }

    /**
     * 추천된 미션 내용 목록 조회
     *
     * @param memberId 사용자 ID
     * @param memberInterestId 멤버 관심사 ID
     * @return 이전에 추천된 미션 내용 목록
     */
    fun getRecommendedMissionContents(memberId: Long, memberInterestId: Long): List<String> {
        val key = MissionRecommendRedisKey.recommendedContents(memberId, memberInterestId, LocalDate.now())
        return try {
            val members = redisTemplate.opsForSet().members(key)
            val contents = members?.map { it.toString() } ?: emptyList()
            logger.info("추천 미션 내용 캐시 조회 - key: $key, count: ${contents.size}")
            contents
        } catch (e: Exception) {
            logger.error("추천 미션 내용 캐시 조회 실패 - key: $key, error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 재추천 횟수 증가 (자정에 자동 만료)
     *
     * @param memberId 사용자 ID
     * @return 증가 후 현재 횟수
     */
    fun incrementRetryCount(memberId: Long): Long {
        val key = MissionRecommendRedisKey.retryCount(memberId)
        return try {
            val count = redisTemplate.opsForValue().increment(key) ?: 1L
            // TTL이 설정되지 않은 경우에만 설정 (첫 번째 증가 시)
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(secondsUntilMidnight()))
            }
            logger.info("재추천 횟수 증가 - memberId: $memberId, count: $count")
            count
        } catch (e: Exception) {
            logger.error("재추천 횟수 증가 실패 - key: $key, error: ${e.message}")
            0L
        }
    }

    /**
     * 재추천 횟수 조회
     *
     * @param memberId 사용자 ID
     * @return 현재 재추천 횟수 (없으면 0)
     */
    fun getRetryCount(memberId: Long): Long {
        val key = MissionRecommendRedisKey.retryCount(memberId)
        return try {
            val count = redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0L
            logger.info("재추천 횟수 조회 - memberId: $memberId, count: $count")
            count
        } catch (e: Exception) {
            logger.error("재추천 횟수 조회 실패 - key: $key, error: ${e.message}")
            0L
        }
    }

    /**
     * 재추천 횟수 초기화
     *
     * @param memberId 사용자 ID
     * @return 초기화 성공 여부
     */
    fun resetRetryCount(memberId: Long): Boolean {
        val key = MissionRecommendRedisKey.retryCount(memberId)
        return try {
            val deleted = redisTemplate.delete(key)
            logger.info("재추천 횟수 초기화 - memberId: $memberId, deleted: $deleted")
            deleted
        } catch (e: Exception) {
            logger.error("재추천 횟수 초기화 실패 - key: $key, error: ${e.message}")
            false
        }
    }
}


object MissionRecommendRedisKey {
    fun retryCount(memberId: Long) =
        "mission:retry:count:$memberId"

    fun recommendedContents(memberId: Long, memberInterestId: Long, date: LocalDate) =
        "today-mission:contents:$memberId:$memberInterestId:$date"
}
