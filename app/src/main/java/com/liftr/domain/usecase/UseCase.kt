package com.liftr.domain.usecase

import kotlinx.coroutines.flow.Flow

/**
 * Base class for use cases that execute a single operation and return a result.
 *
 * Use cases encapsulate business logic and orchestrate the flow of data between
 * repositories and presentation layers. They follow the Single Responsibility Principle
 * by handling one specific business operation.
 *
 * @param P The type of parameters the use case accepts
 * @param R The type of result the use case returns
 */
abstract class UseCase<in P, out R> {
    /**
     * Executes the use case with the given parameters.
     *
     * @param params The parameters required for this use case
     * @return The result of executing the use case
     */
    abstract suspend operator fun invoke(params: P): R
}

/**
 * Base class for use cases that return a Flow for reactive data streams.
 *
 * Use this for operations that need to observe data changes over time,
 * such as watching database updates or configuration changes.
 *
 * @param P The type of parameters the use case accepts
 * @param R The type of data emitted by the Flow
 */
abstract class FlowUseCase<in P, out R> {
    /**
     * Executes the use case and returns a Flow that emits results.
     *
     * @param params The parameters required for this use case
     * @return A Flow that emits results as they become available
     */
    abstract operator fun invoke(params: P): Flow<R>
}

/**
 * Marker for use cases that don't require parameters.
 * Use Unit as the parameter type in this case.
 */
object NoParams
