package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;

class ChionographisBuildException extends BuildException {

    private static final long serialVersionUID = 2661858972149686128L;

    boolean isLoggedAlready_;

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
