/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.List;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;
import org.w3c.dom.Node;

/**
 * A helper class to extract information from XML documents by XPath expressions.
 */
final class XMLUtils {

    /** A private default constructor which inhibits instantiation; that is, a misuse. */
    private XMLUtils() {
    }

    /**
     * Extracts string values from XPath expressions.
     *
     * <p>This method synchronizes on each elemnts of {@code referents}.</p>
     *
     * @param node
     *      a node to apply the XPath expressions.
     * @param referents
     *      the XPath expressions.
     *
     * @return
     *      the extracted string values arranged in a list in the same order as {@code referents}.
     */
    public static List<String> extract(Node node, List<XPathExpression> referents) {
        return referents.stream().map(r -> extractOne(node, r))
                                 .collect(Collectors.toList());
    }

    private static String extractOne(Node node, XPathExpression expr) {
        try {
            synchronized (expr) {
                return (String) expr.evaluate(node, XPathConstants.STRING);
            }
        } catch (XPathExpressionException e) {
            return null;
        }
    }

    /**
     * Parses a qualified name string which has a form of {@code local-part}, {@code
     * namespace-prefix:local-part} or <code>{namespace-uri}local-part</code>.
     *
     * @param name
     *      the qualified name string to be parsed.
     * @param namespaceContext
     *      an object which can resolve {@code namespace-prefix} to {@code namespace-uri}.
     * @param location
     *      the location where this parsing is required.
     *
     * @return
     *      the resulted {@link QName} object, whose {@link QName#prefix() prefix} shall be
     *      {@code namespace-prefix} if <var>name</var> has a form of
     *      {@code namespace-prefix:local-part}, or be {@link QName.DEFAULT_NS_PREFIX} otherwise.
     *
     * @throws BuildException
     *      when the {@code namespace-prefix} has no mappings in <var>namespaceContext</var>.
     */
    public static QName parseQualifiedName(
            String name, NamespaceContext namespaceContext, Location location) {
        if (name.startsWith("{")) {
            return QName.valueOf(name);
        }

        int indexOfColon = name.indexOf(':');
        if (indexOfColon == -1) {
            return new QName(name);
        }

        String prefix = name.substring(0, indexOfColon);
        String namespaceURI = namespaceContext.getNamespaceURI(prefix);
        if (namespaceURI.equals(XMLConstants.NULL_NS_URI)) {
            throw new BuildException(
                "Unbound namespace prefix: " + prefix, location);
        }
        String localName = name.substring(indexOfColon + 1);
        return new QName(namespaceURI, localName, prefix);
    }

    /**
     * Makes a {@code local-part} or {@code namespace-prefix:local-part} form string
     * representation from a {@link QName} object.
     *
     * @param qName
     *      the {@link QName} to make the string representation.
     *
     * @return
     *      the resulted string representation.
     */
    public static String createQualifiedName(QName qName) {
        if (qName.getPrefix().equals(XMLConstants.DEFAULT_NS_PREFIX)) {
            return qName.getLocalPart();
        } else {
            return qName.getPrefix() + ':' + qName.getLocalPart();
        }
    }
}
