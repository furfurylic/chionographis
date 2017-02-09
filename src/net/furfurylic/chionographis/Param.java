/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.ProjectComponent;

/**
 * An object of this class represents one stylesheet parameter applied to the transformation
 * by an <i>Transform</i> driver.
 *
 * @see Transform#createParam()
 */
public final class Param extends ProjectComponent {

    private Function<String, String> expander_;

    private String name_ = null;
    private boolean expand_ = false;
    private Object value_ = null;

    /**
     * Sole constructor.
     *
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     */
    Param(Function<String, String> expander) {
        expander_ = expander;
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
            throw new BuildException(
                "Stylesheet parameters with empty names are not acceptable", getLocation());
        }
        name_ = name;
    }

    /**
     * Sets whether Ant properties in this element's contents are expanded.
     * Defaults to {@code false}.
     *
     * @param expand
     *      {@code true} if properties are expanded; {@code false} otherwise.
     *
     * @since 1.1
     */
    public void setExpand(boolean expand) {
        expand_ = expand;
    }

    /**
     * Sets the value of this parameter.
     * As of now, the value must be a string.
     *
     * @param value
     *      the value of this parameter.
     *
     * @throws BuildException
     *      if property expansion fails.
     */
    public void addText(String value) {
        if (expand_) {
            try {
                value_ = expander_.apply(value);
            } catch (BuildException e) {
                throw new BuildException("Property expansion failed: " + value, e, getLocation());
            }
        } else {
            value_ = value;
        }
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
     *      if the {@linkplain #setName(String) name} is not set
     *      or has an unbound namespace prefix.
     */
    Map.Entry<String, Object> yield(NamespaceContext namespaceContext) {
        if (name_ == null) {
            throw new BuildException(
                "Incomplete stylesheet parameter found: value=" + value_, getLocation());
        }
        Object value = (value_ == null) ? "" : value_;

        String name = name_;
        if (!name.startsWith("{")) {
            int indexOfColon = name.indexOf(':');
            if (indexOfColon != -1) {
                String prefix = name.substring(0, indexOfColon);
                String namespaceURI = namespaceContext.getNamespaceURI(prefix);
                if (namespaceURI.equals(XMLConstants.NULL_NS_URI)) {
                    throw new BuildException(
                        "Unbound namespace prefix: " + prefix, getLocation());
                }
                String localName = name.substring(indexOfColon + 1);
                name = '{' + namespaceURI + '}' + localName;
            }
        }

        return new AbstractMap.SimpleEntry<>(name, value);
    }
}
