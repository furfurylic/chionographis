/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import javax.xml.transform.URIResolver;

/**
 * A bundle of XML-related objects which helps internal implementation of Chionographis.
 */
interface XMLHelper {

    /**
     * Gets the default {@link XMLTransfer} object.
     *
     * @return
     *      the default {@link XMLTransfer} object, which shall not be {@code null}.
     */
    XMLTransfer transfer();

    /**
     * Gets the fallback {@link URIResolver}, which may perform some special resolution but does
     * not perform any caching.
     *
     * @return
     *      the fallback {@link URIResolver}, which may be {@code null}.
     */
    URIResolver fallbackURIResolver();
}
