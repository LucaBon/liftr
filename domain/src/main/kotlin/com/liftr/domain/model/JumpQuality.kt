package com.liftr.domain.model

/**
 * Whether a jump's numbers can be trusted, from its [JumpFitCheck].
 *
 * A flight that doesn't look like free fall means the center of mass was
 * tracked badly (a joint lost, a wrong scale, takeoff or landing a frame off),
 * so the jump's heights are not worth averaging or keeping as a record.
 * A missing check is not a failed one: approach jumps, too short a flight or
 * rows saved before the check existed simply couldn't be checked, and those
 * jumps keep counting as they always have.
 */
enum class JumpQuality {
    /** The flight passed the free-fall check. */
    OK,

    /** The flight failed the free-fall check: tracking didn't match free fall. */
    SUSPECT,

    /** The check couldn't run. */
    UNCHECKED;

    companion object {
        fun of(fitCheck: JumpFitCheck?): JumpQuality = when {
            fitCheck == null -> UNCHECKED
            fitCheck.passed -> OK
            else -> SUSPECT
        }
    }
}

/**
 * The jumps whose numbers count toward averages, bests and trends: every one
 * but the [JumpQuality.SUSPECT] ones. When every jump is suspect, all of them
 * are kept, so a set or a range never ends up with no best at all; callers
 * show a warning instead (see [allJumpsSuspect]).
 */
fun <T> List<T>.jumpsThatCount(quality: (T) -> JumpQuality): List<T> =
    filter { quality(it) != JumpQuality.SUSPECT }.ifEmpty { this }

/** How many jumps [jumpsThatCount] leaves out: 0 when it fell back to all of them. */
fun <T> List<T>.excludedJumpCount(quality: (T) -> JumpQuality): Int = size - jumpsThatCount(quality).size

/** Every jump failed the free-fall check, so [jumpsThatCount] had to keep them all. */
fun <T> List<T>.allJumpsSuspect(quality: (T) -> JumpQuality): Boolean =
    isNotEmpty() && all { quality(it) == JumpQuality.SUSPECT }
