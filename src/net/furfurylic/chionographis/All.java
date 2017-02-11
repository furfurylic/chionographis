/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Resource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import net.furfurylic.chionographis.Logger.Level;

/**
 * An <i>All</i> filter collects all source documents up into an document.
 *
 * <p>The resulted document has all elements of the document elements of the source documents
 * supplied to this object by the driver as the direct child nodes of the document element of the
 * resulted document.</p>
 */
public final class All extends Filter {
    /**
     * The qualified name of the root element.
     *
     * Before {@link #init(File, NamespaceContext, boolean)} is invoked,
     * this field is the user-specified string.
     * After the invocation, this field is the string value of {@link #rootQ_}.
     */
    private String root_ = null;

    private QName rootQ_;

    private Document resultDocument_;
    private Assemblage<LongFunction<Resource>> finders_;

    private Queue<Node> nodes_;

    /**
     * Sole constructor.
     *
     * @param propertyExpander
     *      an object which expands properties in a text, which shall not be {@code null}.
     */
    All(Function<String, String> propertyExpander) {
        super(propertyExpander);
    }

    /**
     * Sets the name of document element of the resulted document.
     *
     * <p>The name is able to be specified in the following three ways:</p>
     * <ul>
     * <li>{@code localName} - an name that doesn't belong any namespaces.</li>
     * <li>{@code {namespaceURI}localName} - an name within an namespace.</li>
     * <li>{@code prefix:localName} - an name within an namespace, which is
     *      {@linkplain Chionographis#createNamespace() mapped from the prefix in the task}.</li>
     * </ul>
     *
     * @param root
     *      the name of document element of the resulted document.
     */
    public void setRoot(String root) {
        root_ = root;
    }

    @Override
    void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun) {
        rootQ_ = null;

        // First take care of "prefix:localName" cases only
        if (!root_.startsWith("{")) {
            int indexOfColon = root_.indexOf(':');
            if (indexOfColon != -1) {
                String prefix = root_.substring(0, indexOfColon);
                String namespaceURI = namespaceContext.getNamespaceURI(prefix);
                if (namespaceURI.equals(XMLConstants.NULL_NS_URI)) {
                    throw new BuildException("Unbound namespace prefix: " + prefix, getLocation());
                }
                String localName = root_.substring(indexOfColon + 1);
                rootQ_ = new QName(namespaceURI, localName, prefix);
            }
        }

        // Other cases
        if (rootQ_ == null) {
            rootQ_ = QName.valueOf(root_);
            if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
                // If rootQ_ is in a certain namespace, make its prefix "all"
                rootQ_ = new QName(rootQ_.getNamespaceURI(), rootQ_.getLocalPart(), "all");
                root_ = rootQ_.getPrefix() + ':' + rootQ_.getLocalPart();
            }
        }

        sink().init(baseDir, namespaceContext, logger(), isForce(), dryRun);
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, LongFunction<Resource>[] finders) {
        boolean[] includes;
        if (isForce()) {
            includes = new boolean[origSrcFileNames.length];
            Arrays.fill(includes, true);
        } else {
            includes = sink().preexamineBundle(origSrcFileNames, finders);
            if (IntStream.range(0, includes.length).anyMatch(i -> includes[i])) {
                Arrays.fill(includes, true);
            }
        }
        return includes;
    }

    @Override
    void startBundle() {
        logger().log(this, "Starting to collect input sources into " + rootQ_, Level.DEBUG);
        sink().startBundle();
        resultDocument_ = XMLTransfer.getDefault().newDocument(getLocation());
        Element docElement = resultDocument_.createElementNS(rootQ_.getNamespaceURI(), root_);
        if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
            // If rootQ_ is in a certain namespace, add the namespace decl
            docElement.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                XMLConstants.XMLNS_ATTRIBUTE + ':' + rootQ_.getPrefix(),
                rootQ_.getNamespaceURI());
        }
        resultDocument_.appendChild(docElement);
        finders_ = new Assemblage<>();
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            LongFunction<Resource> finder, List<String> notUsed) {
        assert resultDocument_ != null;
        synchronized (resultDocument_) {
            finders_.add(finder);
            if (nodes_ != null) {
                return new DOMResult(nodes_.poll());
            }
        }
        return new DOMResult();
    }

    @Override
    void finishOne(Result result) {
        assert resultDocument_ != null;
        assert result != null;
        assert result instanceof DOMResult;
        assert ((DOMResult) result).getNode() != null;
        DOMResult r = (DOMResult) result;
        synchronized (resultDocument_) {
            XMLTransfer.getDefault().transfer(new DOMSource(r.getNode()),
                new DOMResult(resultDocument_.getDocumentElement()), true, getLocation());
        }
        assert r.getNode() != null;
        while (r.getNode().getFirstChild() != null) {
            r.getNode().removeChild(r.getNode().getFirstChild());
        }
        synchronized (resultDocument_) {
            if (nodes_ == null) {
                nodes_ = new ArrayDeque<>();
            }
            nodes_.offer(r.getNode());
        }
    }

    @Override
    Sink abortOne(Result result) {
        // This object collects all of the inputs into one result,
        // so aborting one ruins the whole result.
        logger().log(this, "One of the sources is damaged; must give up all", Level.VERBOSE);
        return this;
    }

    @Override
    void finishBundle() {
        assert resultDocument_ != null;
        List<XPathExpression> referents = sink().referents();
        List<String> referredContents;
        if (!referents.isEmpty()) {
            referredContents = Referral.extract(resultDocument_, referents);
            logger().log(this, "Referred source data: "
                + String.join(", ", referredContents), Level.DEBUG);
        } else {
            referredContents = Collections.emptyList();
        }
        LongFunction<Resource> finder = (long lastModified) ->
            finders_.getList().stream()
                              .map(fn -> fn.apply(lastModified))
                              .filter(f -> f != null)
                              .findAny()
                              .orElse(null);
        Result result = sink().startOne(-1, null, finder, referredContents);
        if (result != null) {
            // Send fragment to sink
            XMLTransfer.getDefault().transfer(
                    new DOMSource(resultDocument_), result, getLocation());
            resultDocument_ = null;
            // Finish sink
            sink().finishOne(result);
        }
        sink().finishBundle();
    }
}
