/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;

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

    private static final long serialVersionUID = 143744476588846591L;

    /**
     * Whether the cause exception of the exception is reported through the logger already or not.
     */
    private boolean isLogged_;

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

    /**
     * Sets the flag of whether the cause exception of this exception is reported already to
     * {@code true}.
     *
     * <p>This flag cannot be set to {@code false} once it has been set to {@code true}.</p>
     *
     * @return
     *      {@code this}
     */
    public NonfatalBuildException setLogged() {
        isLogged_ = (getCause() != null);
        return this;
    }

    /**
     * Tells whether the cause exception of this exception is reported already.
     *
     * @return
     *      {@code true} if the cause exception of this exception is reported already;
     *      {@code false} otherwise.
     */
    public boolean isLogged() {
        return isLogged_;
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
