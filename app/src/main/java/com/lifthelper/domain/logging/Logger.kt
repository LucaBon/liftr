package com.lifthelper.domain.logging

/**
 * Logging abstraction to remove Android dependencies from domain layer.
 *
 * This interface allows domain classes to log messages without directly
 * depending on android.util.Log, making them testable and platform-independent.
 */
interface Logger {
    /**
     * Log a debug message.
     *
     * @param tag Tag to identify the source of the log message
     * @param message The message to log
     */
    fun d(tag: String, message: String)

    /**
     * Log an info message.
     *
     * @param tag Tag to identify the source of the log message
     * @param message The message to log
     */
    fun i(tag: String, message: String)

    /**
     * Log a warning message.
     *
     * @param tag Tag to identify the source of the log message
     * @param message The message to log
     */
    fun w(tag: String, message: String)

    /**
     * Log an error message.
     *
     * @param tag Tag to identify the source of the log message
     * @param message The message to log
     * @param throwable Optional throwable to log
     */
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
