/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.Collections;
import java.util.List;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.tools.ant.BuildException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A <i>Snip</i> filter performs extraction of tree fragments from the source documents
 * and passes the fragments to its sinks.
 *
 * <p>The way of extraction is specified by <i>XPath</i> expression.
 * The root nodes of the fragments are the matched elements with the XPath expression.</p>
 */
public final class Snip extends Sink implements Driver {

    private Sinks sinks_;
    private String select_;
    private boolean force_;

    private NamespaceContext namespaceContext_;
    private XPathExpression expr_;

    private XMLTransfer xfer_;

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

    /**
     * {@inheritDoc}
     */
    @Override
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
        xfer_ = new XMLTransfer();
        force_ = force_ || force;
        sinks_.init(baseDir, namespaceContext, force_);
        namespaceContext_ = namespaceContext;
    }

    @Override
    boolean[] preexamineBundle(String[] originalSrcFileNames, long[] originalSrcLastModifiedTimes) {
        return sinks_.preexamineBundle(originalSrcFileNames, originalSrcLastModifiedTimes);
    }

    @Override
    void startBundle() {
        sinks_.startBundle();
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName,
            long originalSrcLastModifiedTime, List<String> notUsed) {
        return new SnipDOMResult(xfer_.newDocument(), originalSrcIndex, originalSrcFileName, originalSrcLastModifiedTime);
    }

    @Override
    void finishOne(Result result) {
        assert result != null;
        assert result instanceof SnipDOMResult;
        SnipDOMResult r = (SnipDOMResult) result;

        try {
            // Apply XPath expression to current document
            sinks_.log(this, "Applying snipping criteria " + select_ +
                "; the original source is " + r.originalSrcFileName(), Logger.Level.DEBUG);
            NodeList nodes;
            synchronized (this) {   // TODO: synchronization unit is OK?
                if (expr_ == null) {
                    XPath xpath = XPathFactory.newInstance().newXPath();
                    xpath.setNamespaceContext(namespaceContext_);
                    expr_ = xpath.compile(select_);
                }
                nodes = (NodeList) expr_.evaluate(r.getNode(), XPathConstants.NODESET);
            }

            int count = 0;
            for (int i = 0; i < nodes.getLength(); ++i) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Document document = xfer_.newDocument();
                    document.appendChild(document.adoptNode(node));

                    // Search the source contents if necessary
                    List<XPathExpression> referents = sinks_.referents();
                    List<String> referredContents;
                    if (!referents.isEmpty()) {
                        referredContents = Referral.extract(document, referents);
                        sinks_.log(this, "Referred source data: "
                            + String.join(", ", referredContents), Logger.Level.DEBUG);
                    } else {
                        referredContents = Collections.emptyList();
                    }

                    // Open sink's result
                    Result rr = sinks_.startOne(r.originalSrcIndex(), r.originalSrcFileName(),
                        r.originalSrcLastModifiedTime(), referredContents);
                    if (rr != null) {
                        // Send fragment to sink
                        xfer_.transfer(new DOMSource(document), rr);
                        // Finish sink
                        sinks_.finishOne(rr);
                    }

                    ++count;
                }
            }
            if (count > 0) {
                sinks_.log(this, count + " snipped fragments processed", Logger.Level.DEBUG);
            } else {
                sinks_.log(this, "No snipped fragments generated; the original source is " +
                        r.originalSrcFileName(), Logger.Level.INFO);
            }

        } catch (XPathExpressionException e) {
            throw new BuildException(e);
        }
    }

    @Override
    void abortOne(Result result) {
        // sink_.startOne(int, String) is not invoked yet,
        // so we can evade call sink_.abortOne().
    }

    @Override
    void finishBundle() {
        sinks_.finishBundle();
    }

    private static class SnipDOMResult extends DOMResult {
        private int originalSrcIndex_;
        private String originalSrcFileName_;
        private long originalSrcLastModifiedTime_;

        public SnipDOMResult(Document document,
                int originalSrcIndex, String originalSrcFileName,
                long originalSrcLastModifiedTime) {
            super(document);
            originalSrcIndex_ = originalSrcIndex;
            originalSrcFileName_ = originalSrcFileName;
            originalSrcLastModifiedTime_ = originalSrcLastModifiedTime;
        }

        public int originalSrcIndex() {
            return originalSrcIndex_;
        }

        public String originalSrcFileName() {
            return originalSrcFileName_;
        }

        public long originalSrcLastModifiedTime() {
            return originalSrcLastModifiedTime_;
        }
    }
}
