/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;

class FatalityException extends BuildException {

    private static final long serialVersionUID = 6599412493707856208L;

    public FatalityException(Throwable cause) {
        super(cause);
    }
}
