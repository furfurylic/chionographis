/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.types.LogLevel;

interface Logger {

    void log(Object issuer, String message, LogLevel level);
    
}
