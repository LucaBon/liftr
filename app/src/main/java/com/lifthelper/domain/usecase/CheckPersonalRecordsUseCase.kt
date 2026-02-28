package com.lifthelper.domain.usecase

import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PersonalRecord
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.repository.AnalyticsRepository
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
