/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.AbstractMap;
import java.util.Map;

import javax.xml.namespace.NamespaceContext;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;

/**
 * An object of this class represents one stylesheet parameter applied to the transformation
 * by an <i>Transform</i> driver.
 *
 * @see Transform#createParam()
 */
public final class Param {

    private Logger logger_;

    private String name_ = null;
    private Object value_ = null;

    Param(Logger logger) {
        logger_ = logger;
    }

    /**
     * Sets the name of this parameter.
     *
     * <p>The name is able to be specified in the following three ways:</p>
     * <ul>
     * <li>{@code localName} - an name that doesn't belong any namespaces.</li>
     * <li>{@code {namespaceURI}localName} - an name within an namespace.</li>
     * <li>{@code prefix:localName} - an name within an namespace, which is
     *      {@linkplain Chionographis#createNamespace() mapped from the prefix in the task}.</li>
     * </ul>
     *
     * @param name
     *      the name of this parameter.
     */
    public void setName(String name) {
        if (name.isEmpty()) {
            logger_.log(this,
                "Stylesheet parameters with empty names are not acceptable", LogLevel.ERR);
            throw new BuildException();
        }
        name_ = name;
    }

    /**
     * Sets the value of this parameter.
     * As of now, the value must be a string.
     *
     * @param value
     *      the value of this parameter.
     */
    public void addText(String value) {
        value_ = value;
    }

    Map.Entry<String, Object> yield(NamespaceContext namespaceContext) {
        if (name_ == null) {
            String message = "Incomplete stylesheet parameter found";
            if (value_ != null) {
                message += ": value=" + value_;
            }
            logger_.log(this, message, LogLevel.ERR);
            throw new BuildException();
        }
        if (value_ == null) {
            String message = "Incomplete stylesheet parameter found: name=" + name_;
            logger_.log(this, message, LogLevel.ERR);
            throw new BuildException();
        }

        String name = name_;
        if (!name.startsWith("{")) {
            int indexOfColon = name.indexOf(':');
            if (indexOfColon != -1) {
                String prefix = name.substring(0, indexOfColon);
                String localName = name.substring(indexOfColon + 1);
                String namespaceURI = namespaceContext.getNamespaceURI(prefix);
                name = '{' + namespaceURI + '}' + localName;
            }
        }

        return new AbstractMap.SimpleEntry<>(name, value_);
    }
}
