/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * An <i>All</i> {@linkplain Sink sink}/{@linkplain SinkDriver sink driver} collects
 * all source documents up into an document.
 *
 * <p>The resulted document has all elements of the document elements of the source documents
 * supplied to this object by the driver as the direct child nodes of the document element of the
 * resulted document.</p>
 */
public final class All extends Sink implements SinkDriver {

    private String root_ = null;
    private boolean force_ = true;
    private Sinks sinks_;

    private QName rootQ_;

    private Document document_;
    private Document currentDocument_;
    long lastModifiedTime_;

    All(Logger logger) {
        sinks_ = new Sinks(logger);
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

    @Override
    void init(File baseDir, NamespaceContext namespaceContext, boolean force) {
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
        force_ = force_ || force;
        sinks_.init(baseDir, namespaceContext, force_);
    }

    @Override
    boolean[] preexamineBundle(String[] originalSrcFileNames, long[] originalSrcLastModifiedTimes) {
        boolean[] includes;
        if (force_) {
            includes = new boolean[originalSrcFileNames.length];
            Arrays.fill(includes, true);
        } else {
            includes =
                sinks_.preexamineBundle(originalSrcFileNames, originalSrcLastModifiedTimes);
            if (IntStream.range(0, includes.length).anyMatch(i -> includes[i])) {
                Arrays.fill(includes, true);
            }
        }
        return includes;
    }

    @Override
    void startBundle() {
        sinks_.log(this, "Starting to collect input sources into " + rootQ_, LogLevel.DEBUG);
        sinks_.startBundle();
        try {
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            dbfac.setNamespaceAware(true);
            DocumentBuilder builder = dbfac.newDocumentBuilder();
            document_ = builder.newDocument();
            Element docElement = document_.createElementNS(rootQ_.getNamespaceURI(), root_);
            if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
                docElement.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + rootQ_.getPrefix(), rootQ_.getNamespaceURI());
            }
            document_.appendChild(docElement);
            lastModifiedTime_ = Long.MIN_VALUE;
        } catch (ParserConfigurationException e) {
            throw new BuildException(e);
        }
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName, long originalSrcLastModified, List<String> notUsed) {
        assert document_ != null;
        try {
            lastModifiedTime_ = Math.max(originalSrcLastModified, lastModifiedTime_);
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            dbfac.setNamespaceAware(true);
            DocumentBuilder builder = dbfac.newDocumentBuilder();
            currentDocument_ = builder.newDocument();
            return new DOMResult(currentDocument_);
        } catch (ParserConfigurationException e) {
            throw new BuildException(e);
        }
    }

    @Override
    void finishOne() {
        assert document_ != null;
        Node node;
        while ((node = currentDocument_.getFirstChild()) != null) {
            document_.getDocumentElement().appendChild(document_.adoptNode(node));
        }
    }

    @Override
    void abortOne() {
        // This object collects all of the inputs into one result,
        // so aborting one ruins the whole result.
        sinks_.abortOne();
        sinks_.log(this, "One of the sources is damaged; must give up all", LogLevel.ERR);
        throw new BuildException();
    }

    @Override
    void finishBundle() {
        assert document_ != null;
        List<XPathExpression> referents = sinks_.referents(-1, null);
        List<String> referredContents = Referral.extract(document_, referents);
        Result result = sinks_.startOne(-1, null, lastModifiedTime_, referredContents);
        if (result != null) {
            try {
                // Send fragment to sink
                TransformerFactory.newInstance().newTransformer().transform(
                    new DOMSource(document_), result);

                // Finish sink
                sinks_.finishOne();
            } catch (TransformerException e) {
                throw new BuildException(e);
            }
        }
        sinks_.finishBundle();
    }
}
