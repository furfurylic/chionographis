/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A <i>Snip</i> {@linkplain Sink sink}/{@linkplain SinkDriver sink driver} performs
 * extraction of tree fragments from the source documents and passes the fragments to its sinks.
 *
 * <p>The way of extraction is specified by <i>XPath</i> expression.
 * The root nodes of the fragments are the matched elements with the XPath expression.</p>
 */
public final class Snip extends Sink implements SinkDriver {

    private Sinks sinks_;
    private String select_;
    private boolean force_;

    private NamespaceContext namespaceContext_;
    private XPathExpression expr_;

    private Document document_;
    private int currentIndex_;
    private URI currentSrcURI_;
    private String currentSrcFileName_;
    private long currentSrcLastModifiedTime_;

    Snip(Logger logger) {
        sinks_ = new Sinks(logger);
    }

    /**
     * Sets the XPath expression in which the extraction is performed.
     *
     * <p>If the XPath expression contains names within namespaces, the names shall be accompanied
     * by namespace prefixes as specified in XPath specification.
     * You can define prefix-namespace URI mapping entries in
     * {@linkplain Chionographis#createNamespace() the task}.</p>
     *
     * @param xpath
     *      an XPath expression.
     */
    public void setSelect(String xpath) {
        select_ = xpath;
    }

    public void setForce(boolean force) {
        force_ = force;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transform createTransform() {
        return sinks_.createTransform();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public All createAll() {
        return sinks_.createAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snip createSnip() {
        return sinks_.createSnip();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Output createOutput() {
        return sinks_.createOutput();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void init(File baseDir, NamespaceContext namespaceContext, boolean force) {
        force_ = force_ || force;
        sinks_.init(baseDir, namespaceContext, force_);
        namespaceContext_ = namespaceContext;
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> stylesheetURIs) {
        return sinks_.preexamineBundle(originalSrcURIs, originalSrcFileNames, stylesheetURIs);
    }

    @Override
    void startBundle() {
        sinks_.startBundle();
    }

    @Override
    Result startOne(int originalSrcIndex, URI originalSrcURI, String originalSrcFileName,
            long originalSrcLastModifiedTime, List<String> notUsed) {
        currentIndex_ = originalSrcIndex;
        currentSrcURI_ = originalSrcURI;
        currentSrcFileName_ = originalSrcFileName;
        currentSrcLastModifiedTime_ = originalSrcLastModifiedTime;
        document_ = newDocument();
        return new DOMResult(document_);
    }

    private Document newDocument() {
        DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
        dbfac.setNamespaceAware(true);
        try {
            return dbfac.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new BuildException(e);
        }
    }

    @Override
    void finishOne() {
        try {
            // Apply XPath expression to current document
            sinks_.log(this, "Applying snipping criteria " + select_ +
                "; the original source is " + currentSrcFileName_, LogLevel.VERBOSE);
            if (expr_ == null) {
                XPath xpath = XPathFactory.newInstance().newXPath();
                xpath.setNamespaceContext(namespaceContext_);
                expr_ = xpath.compile(select_);
            }
            NodeList nodes = (NodeList) expr_.evaluate(document_, XPathConstants.NODESET);

            int count = 0;
            for (int i = 0; i < nodes.getLength(); ++i) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Document document = newDocument();
                    document.appendChild(document.adoptNode(node));

                    // Search the source contents if necessary
                    List<XPathExpression> referents =
                        sinks_.referents(currentIndex_, currentSrcURI_, currentSrcFileName_);
                    List<String> referredContents = Collections.emptyList();
                    if (!referents.isEmpty()) {
                        sinks_.log(this, "  Referral to the source contents required", LogLevel.DEBUG);
                        referredContents = Referral.extract(document, referents);
                        sinks_.log(this, "  Referred source data: "
                            + String.join(", ", referredContents), LogLevel.DEBUG);
                    } else {
                        sinks_.log(this, "  Referral to the source contents not required", LogLevel.DEBUG);
                    }

                    // Open sink's result
                    Result result = sinks_.startOne(currentIndex_, currentSrcURI_, currentSrcFileName_,
                        currentSrcLastModifiedTime_, referredContents);

                    if (result != null) {
                        // Send fragment to sink
                        TransformerFactory.newInstance().newTransformer().transform(
                            new DOMSource(document), result);

                        // Finish sink
                        sinks_.finishOne();
                    }

                    ++count;
                }
            }
            if (count > 0) {
                sinks_.log(this, count + " snipped fragments processed", LogLevel.VERBOSE);
            } else {
                sinks_.log(this, "No snipped fragments generated; the original source is " + currentSrcFileName_, LogLevel.INFO);
            }

        } catch (TransformerException | XPathExpressionException e) {
            throw new BuildException(e);
        }
    }

    @Override
    void abortOne() {
        // sink_.startOne(int, String) is not invoked yet,
        // so we can evade call sink_.abortOne().
    }

    @Override
    void finishBundle() {
        sinks_.finishBundle();
    }

}
