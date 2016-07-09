/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;

/**
 * Objects of this class are used to instruct their enclosing <i>{@linkplain Chionographis}</i>
 * drivers or <i>{@linkplain Transform}</i> filters that they shall refer the last modified times
 * of the resources the objects of this class point.
 *
 * <p>For examble, if a <i>{@linkplain Transform}</i> filter is configured to use stylesheet
 * {@code style.xsl} and has an object of this class as its child element which points
 * {@code style-included.xsl}, then the filter shall refer both last modified times
 * and recognizes the latest one as its stylesheet's last modified time.</p>
 */
public final class Depends {

    private Logger logger_;

    /** Instructions for the case of the pointed resources do not exist. */
    public enum Absent {
        /** Instructs that the task shall be fail. */
        FAIL,

        /**
         * Instructs that the enclosing driver shall behave as if
         * the pointed resource is <em>very</em> new.
         */
        NEW,

        /** Instructs that the enclosing driver shall not be affected by the absence at all. */
        IGNORE
    }

    private ResourceCollection resources_ = null;
    private Absent absent_ = Absent.FAIL;

    Depends(Logger logger) {
        logger_ = logger;
    }

    /**
     * Sets the instruction for the case that the pointed resources do not exist.
     *
     * @param absent
     *      an instruction which shall be one of {@code fail}, {@code new} and {@code ignore}.
     *      The default value is {@code fail}.
     *
     * @see Absent
     */
    public void setAbsent(String absent) {
        try {
            absent_ = Absent.valueOf(absent.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger_.log(this, "Bad \"absent\" attribute value: " + absent, Logger.Level.ERR);
            throw new BuildException();
        }
    }

    /**
     * Adds the pointed resources.
     *
     * @param resources
     *      the pointed resorces.
     */
    public void add(ResourceCollection resources) {
        resources_ = resources;
    }

    long lastModified() {
        long l = -1;
        for (Resource r : (Iterable<Resource>) (() -> resources_.iterator())) {
            long t = lastModifiedOne(r);
            if (t == 0) {
                return 0;
            } else if (t > 0) {
                l = Math.max(l, t);
            } else {
                // ignore minus
            }
        }
        return (l == -1) ? ofAbsent() : l;
    }

    private long lastModifiedOne(Resource r) {
        if (r.isExists()) {
            return r.getLastModified();     // positive or 0
        } else {
            return ofAbsent();
        }
    }

    private long ofAbsent() {
        switch (absent_) {
        case IGNORE:
            return -1;
        case NEW:
            return 0;
        case FAIL:
            throw new BuildException(); // TODO: logging
        default:
            assert false;
            return 0;
        }
    }
}
