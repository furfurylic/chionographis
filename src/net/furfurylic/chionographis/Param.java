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

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     */
    Param(Logger logger) {
        logger_ = logger;
    }

    /**
     * Sets the name of this parameter. This is a mandatory attribute.
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
                "Stylesheet parameters with empty names are not acceptable", Logger.Level.ERR);
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

    /**
     * Creates a key-value pair from this object's content.
     *
     * @param namespaceContext
     *      a namespace prefix-namespace name mapping, which shall not be {@code null}.
     *
     * @return
     *      a key-value pair for this object's content, which shall not be {@code null}.
     *      The key is the parameter name in {@code localName} or {@code {namespaceURI}localName}
     *      form, and the value is the parameter value.
     *
     * @throws BuildException
     *      if either the {@linkplain #setName(String) name} or the {@linkplain #addText(String)
     *      value} is not set.
     */
    Map.Entry<String, Object> yield(NamespaceContext namespaceContext) {
        if (name_ == null) {
            String message = "Incomplete stylesheet parameter found";
            if (value_ != null) {
                message += ": value=" + value_;
            }
            logger_.log(this, message, Logger.Level.ERR);
            throw new BuildException();
        }
        if (value_ == null) {
            String message = "Incomplete stylesheet parameter found: name=" + name_;
            logger_.log(this, message, Logger.Level.ERR);
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
