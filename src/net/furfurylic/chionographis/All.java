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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
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
    private long lastModifiedTime_;

    private Queue<Node> nodes_;

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     * @param exceptionPoster
     *      an object which consumes exceptions occurred during the preparation process;
     *      which shall not be {@code null}.
     */
    All(Logger logger, Function<String, String> expander,
            Consumer<BuildException> exceptionPoster) {
        super(logger, expander, exceptionPoster);
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
        if (!root_.startsWith("{")) {
            int indexOfColon = root_.indexOf(':');
            if (indexOfColon != -1) {
                String prefix = root_.substring(0, indexOfColon);
                String localName = root_.substring(indexOfColon + 1);
                String namespaceURI = namespaceContext.getNamespaceURI(prefix);
                rootQ_ = new QName(namespaceURI, localName, prefix);
            }
        }
        if (rootQ_ == null) {
            rootQ_ = QName.valueOf(root_);
            if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
                rootQ_ = new QName(rootQ_.getNamespaceURI(), rootQ_.getLocalPart(), "all");
                root_ = rootQ_.getPrefix() + ':' + rootQ_.getLocalPart();
            }
        }
        sink().init(baseDir, namespaceContext, isForce(), dryRun);
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, long[] origSrcLastModTimes) {
        boolean[] includes;
        if (isForce()) {
            includes = new boolean[origSrcFileNames.length];
            Arrays.fill(includes, true);
        } else {
            includes =
                sink().preexamineBundle(origSrcFileNames, origSrcLastModTimes);
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
        resultDocument_ = XMLTransfer.getDefault().newDocument();
        Element docElement = resultDocument_.createElementNS(rootQ_.getNamespaceURI(), root_);
        if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
            docElement.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                XMLConstants.XMLNS_ATTRIBUTE + ':' + rootQ_.getPrefix(),
                rootQ_.getNamespaceURI());
        }
        resultDocument_.appendChild(docElement);
        lastModifiedTime_ = 1;
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            long origSrcLastModTime, List<String> notUsed) {
        assert resultDocument_ != null;
        synchronized (resultDocument_) {
            lastModifiedTime_ = ((origSrcLastModTime <= 0) || (lastModifiedTime_ <= 0)) ?
                0 : Math.max(origSrcLastModTime, lastModifiedTime_);
        }
        synchronized (resultDocument_) {
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
                new DOMResult(resultDocument_.getDocumentElement()), true);
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
    void abortOne(Result result) {
        // This object collects all of the inputs into one result,
        // so aborting one ruins the whole result.
        logger().log(this, "One of the sources is damaged; must give up all", Level.ERR);
        throw new BuildException();
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
        Result result = sink().startOne(-1, null, lastModifiedTime_, referredContents);
        if (result != null) {
            // Send fragment to sink
            XMLTransfer.getDefault().transfer(new DOMSource(resultDocument_), result);
            resultDocument_ = null;
            // Finish sink
            sink().finishOne(result);
        }
        sink().finishBundle();
    }
}
