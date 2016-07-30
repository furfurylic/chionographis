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

import net.furfurylic.chionographis.Logger.Level;

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
            logger_.log(this, "Bad \"absent\" attribute value: " + absent, Level.ERR);
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

    /**
     * Returns the last modified time of the newest resource.
     *
     * @return
     *      -1 if all of the resources are very old (or the caller can neglect its new-ness),
     *      0 if one of the resource is very new,
     *      positive value otherwise.
     */
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
        return (l == -1) ? ofAbsent(null) : l;
    }

    private long lastModifiedOne(Resource r) {
        if (r.isExists()) {
            return r.getLastModified();     // positive or 0
        } else {
            return ofAbsent(r);
        }
    }

    private long ofAbsent(Resource r) {
        switch (absent_) {
        case IGNORE:
            return -1;
        case NEW:
            if (r != null) {
                logger_.log(this,
                    "Referred resource \"" + r.toString() + "\" is missing to be regarded as new",
                    Level.INFO);
            } else {
                logger_.log(this,
                    "Referred resources are missing to be regarded as new", Level.INFO);
            }
            return 0;
        case FAIL:
            if (r != null) {
                logger_.log(this,
                    "Required resource \"" + r.toString() + "\" is missing", Level.ERR);
            } else {
                logger_.log(this, "Required resources are missing", Level.ERR);
            }
            throw new BuildException();
        default:
            assert false;
            return 0;
        }
    }
}
