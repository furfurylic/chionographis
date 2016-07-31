/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.List;
import java.util.stream.Collectors;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.w3c.dom.Node;

/**
 * A helper class to extract information from XML documents by XPath expressions.
 */
final class Referral {

    /** A private default constructor which inhibits instantiation; that is, a misuse. */
    private Referral() {
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
}
