/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;

/**
 * An exception class which signals an serious problem not likely recoverable.
 */
class FatalityException extends BuildException {

    private static final long serialVersionUID = 6599412493707856208L;

    /**
     * Constructs an object with the causal exception object.
     *
     * @param cause
     *      the causal exception object.
     */
    public FatalityException(Throwable cause) {
        super("Fatal situation of the build", cause);
    }
}
