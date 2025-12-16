package com.lifthelper.domain.util

import com.lifthelper.domain.error.LiftHelperError

/**
 * Maps the error of a Result to a different error type.
 *
 * If the Result is successful, it remains unchanged.
 * If the Result is a failure, the error is transformed using the provided function.
 *
 * @param transform Function to transform the error
 * @return Result with transformed error
 */
fun <T> Result<T>.mapError(
    transform: (Throwable) -> LiftHelperError
): Result<T> {
    return when {
        isSuccess -> this
        else -> Result.failure(transform(exceptionOrNull()!!))
    }
}

/**
 * Chains a Result-returning transformation on a successful Result.
 *
 * If the Result is successful, applies the transformation.
 * If the Result is a failure, returns the failure unchanged.
 *
 * @param transform Function to transform the success value into a new Result
 * @return Result of the transformation or original failure
 */
inline fun <T, R> Result<T>.flatMap(
    transform: (T) -> Result<R>
): Result<R> {
    return when {
        isSuccess -> transform(getOrThrow())
        else -> Result.failure(exceptionOrNull()!!)
    }
}

/**
 * Performs an action on a LiftHelperError if the Result is a failure.
 *
 * This is useful for logging or handling specific error types without
 * affecting the Result itself.
 *
 * @param action Function to execute on the error
 * @return The original Result unchanged
 */
fun <T> Result<T>.onError(
    action: (LiftHelperError) -> Unit
): Result<T> {
    exceptionOrNull()?.let { error ->
        if (error is LiftHelperError) {
            action(error)
        }
    }
    return this
}

/**
 * Performs an action on any Throwable if the Result is a failure.
 *
 * This is useful for logging all errors, not just LiftHelperErrors.
 *
 * @param action Function to execute on the error
 * @return The original Result unchanged
 */
fun <T> Result<T>.onAnyError(
    action: (Throwable) -> Unit
): Result<T> {
    exceptionOrNull()?.let { error ->
        action(error)
    }
    return this
}

/**
 * Returns a default value if the Result is a failure.
 *
 * @param defaultValue The value to return on failure
 * @return The success value or the default value
 */
fun <T> Result<T>.getOrDefault(defaultValue: T): T {
    return getOrElse { defaultValue }
}

/**
 * Converts a nullable value to a Result.
 *
 * @param errorProvider Function to provide an error if the value is null
 * @return Result.success if the value is not null, Result.failure otherwise
 */
fun <T : Any> T?.toResult(errorProvider: () -> LiftHelperError): Result<T> {
    return if (this != null) {
        Result.success(this)
    } else {
        Result.failure(errorProvider())
    }
}
