package net.furfurylic.chionographis;

import java.util.List;
import java.util.stream.Collectors;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.w3c.dom.Node;

final class Referral {

    public static List<String> extract(Node node, List<XPathExpression> referents) {
        List<String> referredContents;
        referredContents = referents.stream().map(r -> {
            try {
                return (String) r.evaluate(node, XPathConstants.STRING);
            } catch (XPathExpressionException e) {
                return null;
            }
        }).collect(Collectors.toList());
        return referredContents;
    }

    public static String join(List<String> extracted) {
        return extracted.stream().reduce((s, t) -> s + ", " + t).orElse("");
    }
}
