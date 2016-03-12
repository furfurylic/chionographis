/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.function.BiConsumer;

/**
 * An object of this class represents one stylesheet parameter applied to the transformation
 * by an <i>Transform</i> driver.
 *
 * @see Transform#createParam()
 */
public final class Param {

    private String nameOrValue_;
    private BiConsumer<String, Object> receiver_;

    Param(BiConsumer<String, Object> receiver) {
        receiver_ = receiver;
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
        receiver_.accept(name, nameOrValue_);
        nameOrValue_ = name;
    }

    /**
     * Sets the value of this parameter.
     * As of now, the value must be a string.
     *
     * @param value
     *      the value of this parameter.
     */
    public void addText(String value) {
        if (nameOrValue_ != null) {
            receiver_.accept(nameOrValue_, value);
        }
        nameOrValue_ = value;
    }
}
