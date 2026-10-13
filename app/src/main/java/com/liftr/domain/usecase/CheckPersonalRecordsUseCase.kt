package com.liftr.domain.usecase

import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PersonalRecord
import com.liftr.domain.model.Set
import com.liftr.domain.repository.AnalyticsRepository
import javax.inject.Inject

class CheckPersonalRecordsUseCase @Inject constructor(
    private val analyticsRepository: AnalyticsRepository
) : UseCase<CheckPersonalRecordsUseCase.Params, List<PersonalRecord>>() {

    data class Params(
        val exerciseType: ExerciseType,
        val set: Set,
        val sessionTonnage: Float
    )

    override suspend fun invoke(params: Params): List<PersonalRecord> {
        return analyticsRepository.checkForNewPRs(
            params.exerciseType, params.set, params.sessionTonnage
        )
    }
}
