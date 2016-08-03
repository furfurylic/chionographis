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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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

import net.furfurylic.chionographis.Logger.Level;

/**
 * A <i>Snip</i> filter performs extraction of tree fragments from the source documents
 * and passes the fragments to its sinks.
 *
 * <p>The way of extraction is specified by <i>XPath</i> expression.
 * The root nodes of the fragments are the matched elements with the XPath expression.</p>
 */
public final class Snip extends Filter {

    private String select_;
    private NamespaceContext namespaceContext_;
    private XPathExpression expr_;
    private final ReentrantLock lock_ = new ReentrantLock();

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     */
    Snip(Logger logger, Function<String, String> expander) {
        super(logger, expander);
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
    void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun) {
        sink().init(baseDir, namespaceContext, isForce(), dryRun);
        namespaceContext_ = namespaceContext;
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, long[] origSrcLastModTimes) {
        return sink().preexamineBundle(origSrcFileNames, origSrcLastModTimes);
    }

    @Override
    void startBundle() {
        sink().startBundle();
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            long origSrcLastModTime, List<String> notUsed) {
        return new SnipDOMResult(origSrcIndex, origSrcFileName, origSrcLastModTime);
    }

    @Override
    void finishOne(Result result) {
        assert result != null;
        assert result instanceof SnipDOMResult;
        SnipDOMResult r = (SnipDOMResult) result;

        try {
            // Apply XPath expression to current document
            logger().log(this, "Applying snipping criterion " + select_ +
                "; the original source is " + r.originalSrcFileName(), Level.DEBUG);
            NodeList nodes = extractNodes(r);

            int count;
            ForkJoinPool pool = ForkJoinTask.getPool();
            Stream<Document> fragsStream =
                IntStream.range(0, nodes.getLength())
                         .mapToObj(i -> nodes.item(i))
                         .filter(n -> n.getNodeType() == Node.ELEMENT_NODE)
                         .map(n -> newFragmentDocument(n));
            if (pool != null) {
                // We do document creation sequentially.
                List<Document> documents = fragsStream.collect(Collectors.toList());
                // Created documents are passed to sink in parallel.
                count = pool.submit(() -> documents.stream()
                                                   .parallel()
                                                   .mapToInt(d -> sendFragmentDocument(d, r))
                                                   .sum())
                            .join();
                // It is OK if some fragments failed.
            } else {
                count = fragsStream.mapToInt(d -> sendFragmentDocument(d, r))
                                   .sum();
            }
            if (count > 0) {
                logger().log(this, count + " snipped fragments processed", Level.DEBUG);
            } else {
                logger().log(this, "No snipped fragments generated; the original source is " +
                        r.originalSrcFileName(), Level.INFO);
            }

        } catch (XPathExpressionException e) {
            throw new BuildException(e);
        }
    }

    private NodeList extractNodes(DOMResult result) throws XPathExpressionException {
        lock_.lock();
        try {
            NodeList nodes;
            if (expr_ == null) {
                XPath xpath = XPathFactory.newInstance().newXPath();
                xpath.setNamespaceContext(namespaceContext_);
                expr_ = xpath.compile(select_);
            }
            nodes = (NodeList) expr_.evaluate(result.getNode(), XPathConstants.NODESET);
            return nodes;
        } finally {
            lock_.unlock();
        }
    }

    private Document newFragmentDocument(Node node) {
        Document document = XMLTransfer.getDefault().newDocument();
        document.appendChild(document.adoptNode(node));
        return document;
    }

    private int sendFragmentDocument(Document document, SnipDOMResult result) {
        // Search the source contents if necessary
        List<XPathExpression> referents = sink().referents();
        List<String> referredContents;
        if (!referents.isEmpty()) {
            referredContents = Referral.extract(document, referents);
            logger().log(this, "Referred source data: "
                + String.join(", ", referredContents), Level.DEBUG);
        } else {
            referredContents = Collections.emptyList();
        }

        // Open sink's result
        Result rr = sink().startOne(result.originalSrcIndex(), result.originalSrcFileName(),
            result.originalSrcLastModifiedTime(), referredContents);
        if (rr != null) {
            // Send fragment to sink
            XMLTransfer.getDefault().transfer(new DOMSource(document), rr);
            // Finish sink
            sink().finishOne(rr);
        }

        return 1;
    }

    @Override
    void abortOne(Result result) {
        // sink_.startOne(int, String) is not invoked yet,
        // so we can evade call sink_.abortOne().
    }

    @Override
    void finishBundle() {
        sink().finishBundle();
    }

    private static class SnipDOMResult extends DOMResult {
        private int originalSrcIndex_;
        private String originalSrcFileName_;
        private long originalSrcLastModifiedTime_;

        public SnipDOMResult(
                int originalSrcIndex, String originalSrcFileName,
                long originalSrcLastModifiedTime) {
            super();
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
