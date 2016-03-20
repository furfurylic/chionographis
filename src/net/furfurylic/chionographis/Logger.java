/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

interface Logger {

    public enum Level {
        ERR, WARN, INFO, FINE, VERBOSE, DEBUG
    }

    void log(Object issuer, String message, Level level);

    void log(Object issuer, String message, Throwable ex, Level level);
}
