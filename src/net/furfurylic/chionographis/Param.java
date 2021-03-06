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
import javax.xml.namespace.QName;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.ProjectComponent;
import org.apache.tools.ant.PropertyHelper;

/**
 * An object of this class represents one stylesheet parameter applied to the transformation
 * by an <i>Transform</i> driver.
 *
 * @see Transform#createParam()
 */
public final class Param extends ProjectComponent {

    private String name_ = null;
    private boolean expand_ = false;
    private Object value_ = null;

    /**
     * Sole constructor.
     */
    Param() {
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
                value_ = PropertyHelper.getPropertyHelper(getProject()).replaceProperties(value);
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

        QName name = XMLUtils.parseQualifiedName(name_, namespaceContext, getLocation());
        return new AbstractMap.SimpleEntry<>(name.toString(), value);
    }
}
