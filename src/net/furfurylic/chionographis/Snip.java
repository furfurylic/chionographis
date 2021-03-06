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
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathException;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Resource;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
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

    private String select_ = null;
    private Doctype doctype_ = null;

    private NamespaceContext namespaceContext_;
    private XPathExpression expr_;
    private final ReentrantLock lock_ = new ReentrantLock();

    /** Sole constructor. */
    Snip() {
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
     * Creates a new {@link Doctype} object which instructs this filter to embed a document type
     * declaration.
     *
     * @return
     *      a new {@link Doctype} object
     *
     * @throws BuildException
     *      if this invocation is not the first for this object.
     *
     * @since 1.2
     */
    public Doctype createDoctype() throws BuildException {
        if (doctype_ != null) {
            throw new BuildException("Document types given twice", getLocation());
        }
        doctype_ = new Doctype();
        return doctype_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun) {
        if (doctype_ != null) {
            doctype_.checkSanity();
        }
        sink().init(baseDir, namespaceContext, xmlHelper(), logger(), isForce(), dryRun);
        namespaceContext_ = namespaceContext;
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, LongFunction<Resource>[] finders) {
        return sink().preexamineBundle(origSrcFileNames, finders);
    }

    @Override
    void startBundle() {
        sink().startBundle();
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            LongFunction<Resource> finder, List<String> notUsed) {
        return new SnipDOMResult(origSrcIndex, origSrcFileName, finder);
    }

    @Override
    void finishOne(Result result) {
        assert result != null;
        assert result instanceof SnipDOMResult;
        SnipDOMResult r = (SnipDOMResult) result;

        // Apply XPath expression to current document
        logger().log(this, "Applying snipping criterion " + select_ +
            "; the original source is " + r.origSrcFileName(), Level.DEBUG);
        NodeList nodes = extractNodes(r);

        int count;
        ForkJoinPool pool = ForkJoinTask.getPool();
        Stream<Document> fragsStream =
            IntStream.range(0, nodes.getLength())
                     .mapToObj(i -> nodes.item(i))
                     .filter(n -> n.getNodeType() == Node.ELEMENT_NODE)
                     .map(n -> newFragmentDocument(n));
        if (pool != null) {
            // We create documents sequentially.
            List<Document> documents = fragsStream.collect(Collectors.toList());
            // Created documents are passed to sink in parallel.
            count = pool.submit(() -> documents.stream()
                                               .parallel()
                                               .mapToInt(d -> sendFragmentDocument(d, r))
                                               .sum())
                        .join();
            // It is OK if some fragments have failed.
        } else {
            count = fragsStream.mapToInt(d -> sendFragmentDocument(d, r))
                               .sum();
        }
        if (count > 0) {
            logger().log(this, count + " snipped fragments processed", Level.DEBUG);
        } else {
            logger().log(this, "No snipped fragments generated; the original source is " +
                    r.origSrcFileName(), Level.INFO);
        }
    }

    private NodeList extractNodes(DOMResult result) {
        NodeList nodes;

        lock_.lock();
        try {
            // Compile the XPath once
            if (expr_ == null) {
                XPath xpath = XPathFactory.newInstance().newXPath();
                xpath.setNamespaceContext(namespaceContext_);
                try {
                    expr_ = xpath.compile(select_);
                } catch (XPathException e) {
                    throw new BuildException(
                        "Failed to compile the XPath expression: " + select_, e, getLocation());
                }
            }

            // Apply the XPath to extract nodes
            try {
                nodes = (NodeList) expr_.evaluate(result.getNode(), XPathConstants.NODESET);
            } catch (XPathExpressionException e) {
                throw new NonfatalBuildException(
                    "Failed to apply the XPath expression: " + select_, e, getLocation());
            }
        } finally {
            lock_.unlock();
        }

        // Copy all namespace decls in effect on extracted elements
        // in order to keep valid qualified names of their descendants and themselves
        // (maybe a needless fear...)
        for (int i = 0; i < nodes.getLength(); ++i) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                copyAncestralNamespaceDecls((Element) node);
            }
        }

        return nodes;
    }

    /**
     * Copies all "xmlns" attributes into this element from all of its ancestors.
     *
     * @param e
     *      an element.
     */
    private void copyAncestralNamespaceDecls(Element e) {
        // We can use XPath "namespace::*" on e to know namespace decls in effect at once,
        // but javax.xml.xpath seems to lack plausible ways to pull out the results. Sigh...

        Node ee = e;
        for (;;) {
            ee = ee.getParentNode();
            if ((ee == null) || (ee.getNodeType() != Node.ELEMENT_NODE)) {
                return;
            }
            NamedNodeMap atts = ee.getAttributes();
            for (int j = 0; j < atts.getLength(); ++j) {
                Attr attr = (Attr) atts.item(j);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())
                 && (e.getAttributeNodeNS(attr.getNamespaceURI(), attr.getLocalName()) == null)) {
                    e.setAttributeNodeNS((Attr) attr.cloneNode(true));
                }
            }
        }
    }

    private Document newFragmentDocument(Node node) {
        Document document = xmlHelper().transfer().newDocument(getLocation());
        document.appendChild(document.adoptNode(node));
        return document;
    }

    private int sendFragmentDocument(Document document, SnipDOMResult result) {
        // Search the source contents if necessary
        List<XPathExpression> referents = sink().referents();
        List<String> referredContents;
        if (!referents.isEmpty()) {
            referredContents = XMLUtils.extract(document, referents);
            logger().log(this, "Referred source data: "
                + String.join(", ", referredContents), Level.DEBUG);
        } else {
            referredContents = Collections.emptyList();
        }

        // Open sink's result
        Result rr = sink().startOne(result.origSrcIndex(), result.origSrcFileName(),
            result.finder(), referredContents);
        if (rr != null) {
            // Prepare DOCTYPE
            if (doctype_ != null) {
                doctype_.populateInto(document);
            }
            // Send fragment to sink
            xmlHelper().transfer().transfer(new DOMSource(document), rr, getLocation());
            // Finish sink
            sink().finishOne(rr);
        }

        return 1;
    }

    @Override
    Sink abortOne(Result result) {
        // sink().startOne(int, String) is not invoked yet,
        // so we can evade call sink().abortOne().
        return null;
    }

    @Override
    void finishBundle() {
        sink().finishBundle();
    }

    private static class SnipDOMResult extends DOMResult {
        private int origSrcIndex_;
        private String origSrcFileName_;
        private LongFunction<Resource> finder_;

        public SnipDOMResult(
                int origSrcIndex, String origSrcFileName, LongFunction<Resource> finder) {
            super();
            origSrcIndex_ = origSrcIndex;
            origSrcFileName_ = origSrcFileName;
            finder_ = finder;
        }

        public int origSrcIndex() {
            return origSrcIndex_;
        }

        public String origSrcFileName() {
            return origSrcFileName_;
        }

        public LongFunction<Resource> finder() {
            return finder_;
        }
    }
}
