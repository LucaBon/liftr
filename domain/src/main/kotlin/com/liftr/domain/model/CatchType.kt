package com.liftr.domain.model

/** How an Olympic lift (snatch, clean) was received. */
enum class CatchType {
    /** Pulled and turned over with straight legs: the hips never re-bend under the bar. */
    MUSCLE,

    /** Re-bent under the bar, hips staying above the knees. */
    POWER,

    /** Caught with the hips at or below the knees. */
    SQUAT
}
