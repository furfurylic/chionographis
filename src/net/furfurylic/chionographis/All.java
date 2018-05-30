/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import org.w3c.dom.DocumentFragment;
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
     * After the invocation, this field has a form of prefix:local-part or local-part.
     */
    private String root_ = null;

    /**
     * The qualified name of the root element in Java {@link javax.xml.namespace.QName} version.
     *
     * This field shall be filled up by {@link #init(File, NamespaceContext, boolean)}.
     */
    private QName rootQ_;

    private Doctype doctype_ = null;

    private Document resultDocument_;
    private Assemblage<LongFunction<Resource>> finders_;

    /** Sole constructor. */
    All() {
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

    @Override
    void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun) {
        if (doctype_ != null) {
            doctype_.checkSanity();
        }

        rootQ_ = XMLUtils.parseQualifiedName(root_, namespaceContext, getLocation());

        // If rootQ_ is in a certain namespace and has no prefix, make its prefix "all"
        if ((!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI))
         && rootQ_.getPrefix().equals(XMLConstants.DEFAULT_NS_PREFIX)) {
            rootQ_ = new QName(rootQ_.getNamespaceURI(), rootQ_.getLocalPart(), "all");
        }

        root_ = XMLUtils.createQualifiedName(rootQ_);

        sink().init(baseDir, namespaceContext, xmlHelper(), logger(), isForce(), dryRun);
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
        resultDocument_ = xmlHelper().transfer().newDocument(getLocation());
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
            return new DOMResult(resultDocument_.createDocumentFragment());
        }
    }

    @Override
    void finishOne(Result result) {
        assert resultDocument_ != null;
        assert result != null;
        assert result instanceof DOMResult;
        assert ((DOMResult) result).getNode() != null;
        DOMResult r = (DOMResult) result;
        Node n = r.getNode();
        assert n != null;
        assert n instanceof DocumentFragment;
        assert n.getOwnerDocument() == resultDocument_;
        synchronized (resultDocument_) {
            resultDocument_.getDocumentElement().appendChild(n);
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
            referredContents = XMLUtils.extract(resultDocument_, referents);
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
            // Prepare DOCTYPE
            if (doctype_ != null) {
                doctype_.populateInto(resultDocument_);
            }
            // Send fragment to sink
            xmlHelper().transfer().transfer(
                    new DOMSource(resultDocument_), result, getLocation());
            resultDocument_ = null;
            // Finish sink
            sink().finishOne(result);
        }
        sink().finishBundle();
    }
}
