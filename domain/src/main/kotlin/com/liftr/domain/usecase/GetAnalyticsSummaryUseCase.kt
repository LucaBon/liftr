package com.liftr.domain.usecase

import com.liftr.domain.model.AnalyticsSummary
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.TimeRange
import com.liftr.domain.repository.AnalyticsRepository
import javax.inject.Inject

class GetAnalyticsSummaryUseCase @Inject constructor(
    private val analyticsRepository: AnalyticsRepository
) : UseCase<GetAnalyticsSummaryUseCase.Params, AnalyticsSummary>() {

    data class Params(
        val exerciseType: ExerciseType?,
        val timeRange: TimeRange
    )

    override suspend fun invoke(params: Params): AnalyticsSummary {
        val prs = analyticsRepository.getPersonalRecords(params.exerciseType)

        val velocityTrend = if (params.exerciseType != null) {
            analyticsRepository.getVelocityTrend(params.exerciseType, params.timeRange)
        } else emptyList()

        val volumes = analyticsRepository.getWeeklyVolumes(
            params.exerciseType, params.timeRange
        )

        val e1rmTrend = if (params.exerciseType != null) {
            analyticsRepository.getEstimated1RMTrend(params.exerciseType, params.timeRange)
        } else emptyList()

        val qualityTrend = if (params.exerciseType != null) {
            analyticsRepository.getQualityScoreTrend(params.exerciseType, params.timeRange)
        } else emptyList()

        val recentWorkouts = analyticsRepository.getRecentWorkouts()

        return AnalyticsSummary(
            personalRecords = prs,
            velocityTrend = velocityTrend,
            weeklyVolumes = volumes,
            estimated1RMTrend = e1rmTrend,
            qualityScoreTrend = qualityTrend,
            recentWorkouts = recentWorkouts,
            totalWorkouts = recentWorkouts.size,
            totalSets = recentWorkouts.sumOf { it.sets.size },
            totalReps = recentWorkouts.sumOf { it.getTotalReps() }
        )
    }
}
