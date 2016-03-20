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

final class Referral {

    /**
     * Extracts string values from XPath expressions.
     *
     * @param node
     *      a node to apply the XPath expressions.
     * @param referents
     *      the XPath expressions.
     *
     * @return
     *      the extracted string values arranged in a list in the same order than <i>referents</i>.
     */
    public static List<String> extract(Node node, List<XPathExpression> referents) {
        List<String> referredContents;
        referredContents = referents.stream().map(r -> {
            try {
                synchronized (r) {  // TODO: synchronizing unit is OK?
                    return (String) r.evaluate(node, XPathConstants.STRING);
                }
            } catch (XPathExpressionException e) {
                return null;
            }
        }).collect(Collectors.toList());
        return referredContents;
    }
}
