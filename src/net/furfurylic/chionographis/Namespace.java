/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.function.BiConsumer;

/**
 * This class represents one prefix-namespace URI mapping entry.
 *
 * @see Chionographis#createNamespace()
 */
public final class Namespace {

    private String prefixOrNamespaceURI_;
    private BiConsumer<String, String> receiver_;

    Namespace(BiConsumer<String, String> receiver) {
        receiver_ = receiver;
    }

    /**
     * Sets the namespace prefix.
     *
     * @param prefix
     *      the namespace prefix.
     */
    public void setPrefix(String prefix) {
        receiver_.accept(prefix, prefixOrNamespaceURI_);
        prefixOrNamespaceURI_ = prefix;
    }

    /**
     * Sets the namespace URI.
     *
     * @param namespaceURI
     *      the namespace URI.
     */
    public void addText(String namespaceURI) {
        if (prefixOrNamespaceURI_ != null) {
            receiver_.accept(prefixOrNamespaceURI_, namespaceURI);
        }
        prefixOrNamespaceURI_ = namespaceURI;
    }
}
