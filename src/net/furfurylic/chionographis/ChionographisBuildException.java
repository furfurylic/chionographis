/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;

/**
 * An exception class capable of signaling "this content is logged already".
 *
 * <p><i>{@linkplain Chionographis}</i> task inhibits redundant logging
 * if an instance of this class is caught and it is logged already.</p>
 */
class ChionographisBuildException extends BuildException {

    private static final long serialVersionUID = 2661858972149686128L;

    private boolean isLoggedAlready_;

    public ChionographisBuildException() {
        this(true);
    }

    public ChionographisBuildException(String message) {
        this(message, true);
    }

    public ChionographisBuildException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public ChionographisBuildException(Throwable cause) {
        this(cause, true);
    }

    public ChionographisBuildException(boolean isLoggedAlready) {
        isLoggedAlready_ = isLoggedAlready;
    }

    public ChionographisBuildException(String message, boolean isLoggedAlready) {
        super(message);
        isLoggedAlready_ = isLoggedAlready;
    }

    public ChionographisBuildException(String message, Throwable cause, boolean isLoggedAlready) {
        super(message, cause);
        isLoggedAlready_ = isLoggedAlready;
    }

    public ChionographisBuildException(Throwable cause, boolean isLoggedAlready) {
        super(cause);
        isLoggedAlready_ = isLoggedAlready;
    }

    public boolean isLoggedAlready() {
        return isLoggedAlready_;
    }
}
