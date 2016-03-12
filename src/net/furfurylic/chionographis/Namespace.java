/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.AbstractMap;
import java.util.Map;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;

/**
 * This class represents one prefix-namespace URI mapping entry.
 *
 * @see Chionographis#createNamespace()
 */
public final class Namespace {

    private Logger logger_;

    private String prefix_ = null;
    private String namespaceURI_ = null;

    Namespace(Logger logger) {
        logger_ = logger;
    }

    /**
     * Sets the namespace prefix.
     *
     * @param prefix
     *      the namespace prefix.
     */
    public void setPrefix(String prefix) {
        if (prefix.isEmpty()) {
            logger_.log(this, "Empty namespace prefixes are not acceptable", LogLevel.ERR);
            throw new BuildException();
        }
        if ((prefix.length() >= 3) && prefix.substring(0, 3).equalsIgnoreCase("xml")) {
            logger_.log(this, "Bad namespace prefix: " + prefix, LogLevel.ERR);
            throw new BuildException();
        }
        prefix_ = prefix;
    }

    /**
     * Sets the namespace URI.
     *
     * @param uri
     *      the namespace URI.
     */
    public void setURI(String uri) {
        namespaceURI_ = uri;
    }

    Map.Entry<String, String> yield() {
        if (prefix_ == null) {
            String message = "Incomplete namespace prefix config found";
            if (namespaceURI_ != null) {
                message += ": URI=" + namespaceURI_;
            }
            logger_.log(this, message, LogLevel.ERR);
            throw new BuildException();
        }
        if (namespaceURI_ == null) {
            String message = "Incomplete namespace prefix config found: prefix=" + prefix_;
            logger_.log(this, message, LogLevel.ERR);
            throw new BuildException();
        }

        return new AbstractMap.SimpleImmutableEntry<String, String>(prefix_, namespaceURI_);
    }
}
