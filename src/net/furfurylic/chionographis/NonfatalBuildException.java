/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;

/**
 * An exception class which signals nonfatal errors.
 *
 * <p>A nonfatal error terminates a process for the original source concerned
 * but does not ruin the whole process of {@link Chionographis} task.</p>
 *
 * <p>An instances of this exception class has a flag of whether the cause exception of
 * the exception is reported through the logger already or not.
 * The flag defaults to {@code false}.</p>
 */
class NonfatalBuildException extends BuildException {

    private static final long serialVersionUID = -3814186559367393576L;

    public NonfatalBuildException() {
    }

    public NonfatalBuildException(String message) {
        super(message);
    }

    public NonfatalBuildException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonfatalBuildException(Throwable cause) {
        super(cause);
    }

    public NonfatalBuildException(String message, Location location) {
        super(message, location);
    }

    public NonfatalBuildException(String msg, Throwable cause, Location location) {
        super(msg, cause, location);
    }

    public NonfatalBuildException(Throwable cause, Location location) {
        super(cause, location);
    }

    @Override
    public String toString() {
        String bySuper = super.toString();
        if (bySuper.isEmpty()) {
            return this.getClass().getName();
        } else {
            if (!bySuper.startsWith(this.getClass().getName())) {
                return this.getClass().getName() + ": " + bySuper;
            } else {
                return bySuper;
            }
        }
    }
}
