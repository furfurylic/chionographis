/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
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
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

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

    private NamespaceContext namespaceContext_;
    private XPathExpression expr_;
    
    private Document document_;
    private DocumentFragment fragment_;
    private int currentIndex_;
    private String currentSrcFileName_;

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
    void init(File baseDir, NamespaceContext namespaceContext) {
        sinks_.init(baseDir, namespaceContext);
        namespaceContext_ = namespaceContext;
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> additionalURIs) {
        return sinks_.preexamineBundle(originalSrcURIs, originalSrcFileNames, additionalURIs);
    }

    @Override
    void startBundle() {
        sinks_.startBundle();
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName) {
        currentIndex_ = originalSrcIndex;
        currentSrcFileName_ = originalSrcFileName;

        if (document_ == null) {
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            dbfac.setNamespaceAware(true);
            try {
                document_ = dbfac.newDocumentBuilder().newDocument();
                fragment_ = document_.createDocumentFragment(); 
            } catch (ParserConfigurationException e) {
                throw new BuildException(e);
            }
        }

        return new DOMResult(fragment_);
    }

    @Override
    void finishOne(String notUsed) {
        try {           
            // Apply XPath expression to current document 
            sinks_.log(this, "Applying snipping criteria \"" + select_ +"\"; input source is " + currentSrcFileName_, LogLevel.VERBOSE);
            if (expr_ == null) {
                XPath xpath = XPathFactory.newInstance().newXPath();
                xpath.setNamespaceContext(namespaceContext_);
                expr_ = xpath.compile(select_);
            }
            NodeList nodes = (NodeList) expr_.evaluate(fragment_, XPathConstants.NODESET);

            int count = 0;
            for (int i = 0; i < nodes.getLength(); ++i) {
                Node node = nodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    // Open sink's result
                    Result result = sinks_.startOne(currentIndex_, currentSrcFileName_);
                    
                    // Search output if necessary
                    String output = null;
                    if (sinks_.needsOutput()) {
                        sinks_.log(this, "  PI search required", LogLevel.DEBUG);
                        output = extractOutput(node);                        
                        sinks_.log(this, "  PI data is " + output, LogLevel.DEBUG);
                    } else {
                        sinks_.log(this, "  PI search not required", LogLevel.DEBUG);
                    }
                    
                    // Send fragment to sink
                    DocumentFragment frag = fragment_.getOwnerDocument().createDocumentFragment();
                    frag.appendChild(node);
                    TransformerFactory.newInstance().newTransformer().transform(
                        new DOMSource(frag), result);

                    // Finish sink
                    sinks_.finishOne(output);

                    ++count;
                }
            }
            if (count > 0) {
                sinks_.log(this, count + " snipped fragments processed", LogLevel.VERBOSE);
            } else {
                sinks_.log(this, "No snipped fragments generated; input source is " + currentSrcFileName_, LogLevel.INFO);
            }

        } catch (TransformerException | XPathExpressionException e) {
            e.printStackTrace();
            throw new BuildException(e);
        }
    }

    @Override
    void abortOne() {
        // sink_.startOne(int, String) is not invoked yet,
        // so we can evade call sink_.abortOne().
    }

    private String extractOutput(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            switch (child.getNodeType()) {
            case Node.PROCESSING_INSTRUCTION_NODE:
                {
                    ProcessingInstruction pi = (ProcessingInstruction) child;
                    if (pi.getTarget().equals("chionographis-output")) {
                        node.removeChild(pi);
                        return pi.getData();
                    }
                }
                break;
            case Node.ELEMENT_NODE:
                return null;
            default:
                break;
            }
            child = child.getNextSibling();            
        }
        return null;
    }

    @Override
    void finishBundle() {
        sinks_.finishBundle();
    }

}
