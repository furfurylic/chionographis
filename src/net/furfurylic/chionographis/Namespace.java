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
import org.apache.tools.ant.ProjectComponent;

/**
 * This class represents one prefix-namespace URI mapping entry.
 *
 * @see Chionographis#createNamespace()
 */
public final class Namespace extends ProjectComponent {

    private String prefix_ = null;
    private String namespaceURI_ = null;

    /**
     * Sole constructor.
     */
    Namespace() {
    }

    /**
     * Sets the namespace prefix. This is a mandatory attribute.
     *
     * @param prefix
     *      the namespace prefix.
     *
     * @throws BuildException
     *      if {@code prefix} violates the rule in the XML specification.
     */
    public void setPrefix(String prefix) {
        if (prefix.isEmpty()) {
            throw new BuildException(
                "Empty namespace prefixes are not acceptable", getLocation());
        } else if ((prefix.length() >= 3) && prefix.substring(0, 3).equalsIgnoreCase("xml")) {
            throw new BuildException("Bad namespace prefix: " + prefix, getLocation());
        } else {
            prefix_ = prefix;
        }
    }

    /**
     * Sets the namespace URI. This is a mandatory attribute.
     *
     * @param uri
     *      the namespace URI.
     */
    public void setURI(String uri) {
        namespaceURI_ = uri;
    }

    /**
     * Creates a key-value pair from this object's content.
     *
     * @return
     *      a key-value pair for this object's content, which shall not be {@code null}.
     *      The key is the namespace prefix, and the value is the namespace URI.
     *
     * @throws BuildException
     *      if either the {@linkplain #setPrefix(String) prefix}
     *      or the {@linkplain #setURI(String) URI} is not set.
     */
    Map.Entry<String, String> yield() {
        if (prefix_ == null) {
            String message = "Incomplete namespace prefix mapping found";
            if (namespaceURI_ != null) {
                message += ": URI=" + namespaceURI_;
            }
            throw new BuildException(message, getLocation());
        }
        if (namespaceURI_ == null) {
            String message = "Incomplete namespace prefix mapping found: prefix=" + prefix_;
            throw new BuildException(message, getLocation());
        }

        return new AbstractMap.SimpleImmutableEntry<String, String>(prefix_, namespaceURI_);
    }
}
