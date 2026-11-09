package com.liftr.ui.screens.workout

import com.liftr.domain.model.Set

/**
 * A plate-tracked set whose plate was not found, and the raw recording kept so the
 * lifter can tap the plate and analyze again.
 *
 * @property videoPath The raw recording that was analyzed (kept while the offer stands)
 * @property setKey [Set.timestamp] of the set saved from this analysis; null when none was saved
 * @property tapped True for the result of a tap re-analysis (the plate is still not found)
 */
data class PlateReanalysis(
    val videoPath: String,
    val setKey: Long?,
    val tapped: Boolean = false
)

enum class AnalyzedSetAction {
    /** A new analysis: the set joins the session. */
    APPEND,

    /** A tap re-analysis: it takes the place of the set it was made from. */
    REPLACE,

    /** A tap re-analysis whose original set is no longer in the session (saved or none): nothing to change. */
    SKIP
}

data class AnalyzedSetUpdate(val sets: List<Set>, val action: AnalyzedSetAction)

/**
 * Applies an analyzed [newSet] to the session's [sets]. A tap re-analysis ([reanalysis]
 * with a [PlateReanalysis.setKey]) replaces that set in place, so it never counts
 * twice. If that set is gone (the workout was already saved) nothing changes; when
 * the first analysis saved no set the new one is appended.
 */
fun applyAnalyzedSet(sets: List<Set>, newSet: Set, reanalysis: PlateReanalysis?): AnalyzedSetUpdate {
    val key = reanalysis?.setKey ?: return AnalyzedSetUpdate(sets + newSet, AnalyzedSetAction.APPEND)
    val index = sets.indexOfFirst { it.timestamp == key }
    if (index < 0) return AnalyzedSetUpdate(sets, AnalyzedSetAction.SKIP)
    return AnalyzedSetUpdate(sets.toMutableList().also { it[index] = newSet }, AnalyzedSetAction.REPLACE)
}
