package com.haruUp.category.controller

import com.haruUp.category.application.JobUseCase
import com.haruUp.category.domain.dto.JobDetailDto
import com.haruUp.category.domain.dto.JobDto
import com.haruUp.global.security.MemberPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/job")
class JobController (
    private val jobUseCase: JobUseCase
) {

    @GetMapping("/getJobList")
    fun getJobList(@AuthenticationPrincipal principal: MemberPrincipal) : List<JobDto>{
       return jobUseCase.getJobList()
    }

    @GetMapping("/getJobDetailList")
    fun getJobDetailList(@AuthenticationPrincipal principal: MemberPrincipal, @RequestParam jobId : Long) : List<JobDetailDto> {
        return jobUseCase.getJobDetail(jobId)
    }



}