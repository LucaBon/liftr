package com.liftr.data.repository

import com.liftr.data.local.database.dao.RepDao
import com.liftr.data.local.database.dao.SetDao
import com.liftr.data.local.database.dao.WorkoutDao
import com.liftr.data.local.database.entities.SetEntity
import com.liftr.data.mapper.RepMapper
import com.liftr.data.mapper.SetMapper
import com.liftr.data.mapper.WorkoutMapper
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.LoadVelocityPoint
import com.liftr.domain.model.LoadVelocityProfile
import com.liftr.domain.model.PersonalRecord
import com.liftr.domain.model.PersonalRecordType
import com.liftr.domain.model.TimeRange
import com.liftr.domain.model.TrendDataPoint
import com.liftr.domain.model.WeeklyVolume
import com.liftr.domain.model.Workout
import com.liftr.domain.repository.AnalyticsRepository
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.MetersPerSecond
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsRepositoryImpl @Inject constructor(
    private val setDao: SetDao,
    private val repDao: RepDao,
    private val workoutDao: WorkoutDao,
    private val setMapper: SetMapper,
    private val repMapper: RepMapper,
    private val workoutMapper: WorkoutMapper
) : AnalyticsRepository {

    override suspend fun getPersonalRecords(exerciseType: ExerciseType?): List<PersonalRecord> {
        val exerciseTypes = if (exerciseType != null) listOf(exerciseType)
        else ExerciseType.entries.toList()

        val records = mutableListOf<PersonalRecord>()

        for (et in exerciseTypes) {
            // Heaviest set
            setDao.getHeaviestSet(et)?.let { entity ->
                records.add(PersonalRecord(
                    type = PersonalRecordType.HEAVIEST_SET,
                    exerciseType = et,
                    value = "${entity.weight.toInt()} kg x ${entity.completedReps}",
                    numericValue = entity.weight,
                    date = entity.timestamp,
                    workoutId = entity.workoutId
                ))
            }

            // Fastest rep
            repDao.getFastestRep(et)?.let { entity ->
                records.add(PersonalRecord(
                    type = PersonalRecordType.FASTEST_REP,
                    exerciseType = et,
                    value = "%.2f m/s".format(entity.peakVelocity),
                    numericValue = entity.peakVelocity,
                    date = entity.timestamp,
                    workoutId = 0
                ))
            }

            // Best quality
            repDao.getBestQualityRep(et)?.let { entity ->
                if (entity.qualityScore > 0) {
                    records.add(PersonalRecord(
                        type = PersonalRecordType.BEST_QUALITY,
                        exerciseType = et,
                        value = "${entity.qualityScore}/100",
                        numericValue = entity.qualityScore.toFloat(),
                        date = entity.timestamp,
                        workoutId = 0
                    ))
                }
            }

            // Highest volume set
            setDao.getHighestVolumeSet(et)?.let { entity ->
                val volume = entity.weight * entity.completedReps
                records.add(PersonalRecord(
                    type = PersonalRecordType.HIGHEST_VOLUME_SET,
                    exerciseType = et,
                    value = "${volume.toInt()} kg",
                    numericValue = volume,
                    date = entity.timestamp,
                    workoutId = entity.workoutId
                ))
            }

            // Estimated 1RM
            val allSets = setDao.getSetsForExerciseInRange(et, 0L)
            val lvPoints = allSets
                .filter { it.completedReps > 0 }
                // A zero mean velocity is not a measurement — it means the set
                // carries no usable velocity data. Regressing load against it
                // drags the fitted line toward a nonsense 1RM.
                .filter { it.avgVelocity > 0f }
                .map { LoadVelocityPoint(
                    load = Kilograms(it.weight),
                    meanVelocity = MetersPerSecond(it.avgVelocity),
                    timestamp = it.timestamp
                ) }
            if (lvPoints.size >= LoadVelocityProfile.MIN_DATA_POINTS) {
                val profile = LoadVelocityProfile(
                    exerciseType = et,
                    dataPoints = lvPoints,
                    minVelocityAt1RM = LoadVelocityProfile.getMinVelocityAt1RM(et)
                )
                profile.estimatedOneRepMax()?.let { oneRM ->
                    records.add(PersonalRecord(
                        type = PersonalRecordType.ESTIMATED_1RM,
                        exerciseType = et,
                        value = "${oneRM.value.toInt()} kg",
                        numericValue = oneRM.value,
                        date = lvPoints.maxOf { it.timestamp },
                        workoutId = 0
                    ))
                }
            }
        }

        return records
    }

    override suspend fun getVelocityTrend(
        exerciseType: ExerciseType,
        timeRange: TimeRange,
        weightFilter: Kilograms?
    ): List<TrendDataPoint> {
        val fromTimestamp = getFromTimestamp(timeRange)
        val sets = setDao.getSetsForExerciseInRange(exerciseType, fromTimestamp)
        return sets
            .filter { it.completedReps > 0 }
            .filter { weightFilter == null || it.weight == weightFilter.value }
            .map { TrendDataPoint(
                timestamp = it.timestamp,
                value = it.avgVelocity,
                label = "${it.weight.toInt()} kg"
            ) }
    }

    override suspend fun getWeeklyVolumes(
        exerciseType: ExerciseType?,
        timeRange: TimeRange
    ): List<WeeklyVolume> {
        val fromTimestamp = getFromTimestamp(timeRange)
        val sets = setDao.getAllSetsInRange(fromTimestamp)
            .filter { it.completedReps > 0 }
            .filter { exerciseType == null || it.exerciseType == exerciseType }

        return sets
            .groupBy { getWeekStart(it.timestamp) }
            .flatMap { (weekStart, weekSets) ->
                if (exerciseType != null) {
                    val tonnage = weekSets.sumOf { (it.weight * it.completedReps).toDouble() }.toFloat()
                    listOf(WeeklyVolume(weekStart, exerciseType, Kilograms(tonnage)))
                } else {
                    weekSets.groupBy { it.exerciseType }.map { (et, etSets) ->
                        val tonnage = etSets.sumOf { (it.weight * it.completedReps).toDouble() }.toFloat()
                        WeeklyVolume(weekStart, et, Kilograms(tonnage))
                    }
                }
            }
            .sortedBy { it.weekStartTimestamp }
    }

    override suspend fun getEstimated1RMTrend(
        exerciseType: ExerciseType,
        timeRange: TimeRange
    ): List<TrendDataPoint> {
        val fromTimestamp = getFromTimestamp(timeRange)
        val sets = setDao.getSetsForExerciseInRange(exerciseType, fromTimestamp)
            .filter { it.completedReps > 0 }

        if (sets.size < LoadVelocityProfile.MIN_DATA_POINTS) return emptyList()

        val trend = mutableListOf<TrendDataPoint>()
        val cumulativePoints = mutableListOf<LoadVelocityPoint>()

        for (set in sets) {
            cumulativePoints.add(LoadVelocityPoint(
                load = Kilograms(set.weight),
                meanVelocity = MetersPerSecond(set.avgVelocity),
                timestamp = set.timestamp
            ))

            if (cumulativePoints.size >= LoadVelocityProfile.MIN_DATA_POINTS) {
                val profile = LoadVelocityProfile(
                    exerciseType = exerciseType,
                    dataPoints = cumulativePoints.toList(),
                    minVelocityAt1RM = LoadVelocityProfile.getMinVelocityAt1RM(exerciseType)
                )
                profile.estimatedOneRepMax()?.let { oneRM ->
                    trend.add(TrendDataPoint(
                        timestamp = set.timestamp,
                        value = oneRM.value,
                        label = "${oneRM.value.toInt()} kg"
                    ))
                }
            }
        }

        return trend
    }

    override suspend fun getQualityScoreTrend(
        exerciseType: ExerciseType,
        timeRange: TimeRange
    ): List<TrendDataPoint> {
        val fromTimestamp = getFromTimestamp(timeRange)
        val sets = setDao.getSetsForExerciseInRange(exerciseType, fromTimestamp)

        val trend = mutableListOf<TrendDataPoint>()
        for (set in sets) {
            val reps = repDao.getRepsForSetSync(set.id)
            if (reps.isNotEmpty()) {
                val avgQuality = reps.map { it.qualityScore }.average().toFloat()
                if (avgQuality > 0f) {
                    trend.add(TrendDataPoint(
                        timestamp = set.timestamp,
                        value = avgQuality
                    ))
                }
            }
        }
        return trend
    }

    override suspend fun getRecentWorkouts(limit: Int): List<Workout> {
        val workoutEntities = workoutDao.getRecentWorkouts(limit)
        return workoutEntities.map { workoutEntity ->
            val setEntities = setDao.getSetsForWorkoutSync(workoutEntity.id)
            val sets = setEntities.map { setEntity ->
                val repEntities = repDao.getRepsForSetSync(setEntity.id)
                val reps = repEntities.map { repMapper.toDomain(it) }
                setMapper.toDomain(setEntity, reps)
            }
            workoutMapper.toDomain(workoutEntity, sets)
        }
    }

    override suspend fun getAvailableWeights(exerciseType: ExerciseType): List<Kilograms> {
        return setDao.getDistinctWeights(exerciseType).map { Kilograms(it) }
    }

    override suspend fun checkForNewPRs(
        exerciseType: ExerciseType,
        set: com.liftr.domain.model.Set,
        sessionTonnage: Float
    ): List<PersonalRecord> {
        val now = System.currentTimeMillis()
        val newPRs = mutableListOf<PersonalRecord>()

        // Check heaviest set
        val currentHeaviest = setDao.getHeaviestSet(exerciseType)
        if (currentHeaviest == null || set.weight.value > currentHeaviest.weight) {
            newPRs.add(PersonalRecord(
                type = PersonalRecordType.HEAVIEST_SET,
                exerciseType = exerciseType,
                value = "${set.weight.value.toInt()} kg x ${set.completedReps.value}",
                numericValue = set.weight.value,
                date = now,
                workoutId = 0
            ))
        }

        // Check fastest rep
        val peakVelocity = set.peakVelocity.value
        val currentFastest = repDao.getFastestRep(exerciseType)
        if (peakVelocity > 0 && (currentFastest == null || peakVelocity > currentFastest.peakVelocity)) {
            newPRs.add(PersonalRecord(
                type = PersonalRecordType.FASTEST_REP,
                exerciseType = exerciseType,
                value = "%.2f m/s".format(peakVelocity),
                numericValue = peakVelocity,
                date = now,
                workoutId = 0
            ))
        }

        // Check highest volume set
        val setVolume = set.weight.value * set.completedReps.value
        val currentHighestVolume = setDao.getHighestVolumeSet(exerciseType)
        val currentHighestVolumeValue = if (currentHighestVolume != null) {
            currentHighestVolume.weight * currentHighestVolume.completedReps
        } else 0f
        if (setVolume > currentHighestVolumeValue) {
            newPRs.add(PersonalRecord(
                type = PersonalRecordType.HIGHEST_VOLUME_SET,
                exerciseType = exerciseType,
                value = "${setVolume.toInt()} kg",
                numericValue = setVolume,
                date = now,
                workoutId = 0
            ))
        }

        return newPRs
    }

    private fun getFromTimestamp(timeRange: TimeRange): Long {
        if (timeRange == TimeRange.ALL) return 0L
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -timeRange.days)
        return calendar.timeInMillis
    }

    private fun getWeekStart(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
