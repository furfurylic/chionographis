/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

/**
 * A logger class used from Chionographis's implementation.
 */
interface Logger {

    /** The enumrated value of the priority of the log entries. */
    enum Level {
        /**
         * ERR log priority. Logging with this priority generally means an unrecoverable case.
         */
        ERR,
        /** WARN log priority. */
        WARN,
        /** INFO log priority. */
        INFO,
        /** FINE log priority. */
        FINE,
        /** VERBOSE log priority. */
        VERBOSE,
        /** DEBUG log priority. */
        DEBUG
    }

    /**
     * Logs a message with the given priority.
     *
     * @param issuer
     *      the issuer object of this log entry, which shall not be {@code null}.
     * @param message
     *      the message to be logged, which shall not be {@code null}.
     * @param level
     *      the priority of the log entry, which shall not be {@code null}.
     */
    void log(Object issuer, String message, Level level);

    /**
     * Logs a exception with the given priority.
     *
     * @param issuer
     *      the issuer object of this log entry, which shall not be {@code null}.
     * @param ex
     *      the exception object to be logged, which shall not be {@code null}.
     * @param heading
     *      the heading added to the stack trace of {@code ex}.
     *      This can be an empty string, but shall not be {@code null}.
     * @param headingLevel
     *      the log priority of the first line of the stack trace of {@code ex},
     *      which shall not be {@code null}.
     * @param bodyLevel
     *      the log priority of the second and subsequent lines of the stack trace of {@code ex},
     *      which shall not be {@code null}.
     */
    void log(Object issuer, Throwable ex, String heading, Level headingLevel, Level bodyLevel);
}

