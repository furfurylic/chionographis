/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.transform.Result;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;

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
    private AllHandler handler_;

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
    void init(File baseDir, NamespaceContext namespaceContext) {
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
        sinks_.init(baseDir, namespaceContext);
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> additionalURIs) {
        if (force_) {
            boolean[] result = new boolean[originalSrcURIs.length];
            Arrays.fill(result, true);
            return result;
        } else {
            boolean[] includes =
                sinks_.preexamineBundle(originalSrcURIs, originalSrcFileNames, additionalURIs);
            if (IntStream.range(0, includes.length).anyMatch(i -> includes[i])) {
                Arrays.fill(includes, true);
            }
            return includes;
        }
    }

    @Override
    void startBundle() {
        sinks_.log(this, "Starting to collect input sources into " + rootQ_, LogLevel.DEBUG);
        sinks_.startBundle();
        Result result = sinks_.startOne(-1, null);
        if (result instanceof SAXResult) {
            SAXResult saxResult = (SAXResult) result;
            handler_ = new AllHandler(saxResult.getHandler(), saxResult.getLexicalHandler());
        } else {
            try {
                TransformerHandler identity = ((SAXTransformerFactory) TransformerFactory
                    .newInstance()).newTransformerHandler();
                identity.setResult(result);
                handler_ = new AllHandler(identity, identity);
            } catch (TransformerException e) {
                throw new BuildException(e);
            }
        }
        try {
            handler_.getHandler().setDocumentLocator(handler_);
            handler_.getHandler().startDocument();
            AttributesImpl atts = new AttributesImpl();
            if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
                handler_.getHandler().startPrefixMapping(
                    rootQ_.getPrefix(), rootQ_.getNamespaceURI());
                atts.addAttribute("", rootQ_.getPrefix(), "xmlns:" + rootQ_.getPrefix(), "CDATA", rootQ_.getNamespaceURI());
            }
            handler_.getHandler().startElement(
                rootQ_.getNamespaceURI(), rootQ_.getLocalPart(), root_, atts);
        } catch (SAXException e) {
            e.printStackTrace();
            throw new BuildException(e);
        }
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName) {
        sinks_.log(this, "Receiving input source, which is " + originalSrcFileName, LogLevel.DEBUG);        
        return new SAXResult(handler_);
    }

    @Override
    void finishOne(List<String> notUsed) {
    }

    @Override
    void abortOne() {
        // This object collects all of the inputs into one result,
        // so aborting one ruins the whole result.
        try {
            sinks_.abortOne();
        } catch (BuildException e) {
            // TODO: Any logging?
        }
        throw new BuildException(); // TODO: message
    }

    @Override
    void finishBundle() {
        try {
            handler_.getHandler().endElement(
                rootQ_.getNamespaceURI(), rootQ_.getLocalPart(), root_);
            if (!rootQ_.getNamespaceURI().equals(XMLConstants.NULL_NS_URI)) {
                handler_.getHandler().endPrefixMapping(rootQ_.getPrefix());
            }
            handler_.getHandler().endDocument();
        } catch (SAXException e) {
            throw new BuildException(e);
        }
        // TODO: what if documentCount == 0?
        sinks_.log(this, "Finishing 1 output containing " + handler_.documentCount() + " input sources ", LogLevel.VERBOSE);
        sinks_.finishOne(Collections.<String>emptyList());
        sinks_.finishBundle();
    }

    private static final class AllHandler implements ContentHandler, LexicalHandler, Locator {
        ContentHandler contentHandler_;
        LexicalHandler lexicalHandler_;
        Locator currentLocator_;
        int count_;

        public AllHandler(ContentHandler contentHandler, LexicalHandler lexicalHandler) {
            contentHandler_ = contentHandler;
            lexicalHandler_ = lexicalHandler;
            currentLocator_ = null;
            count_ = 0;
        }

        public int documentCount() {
            return count_;
        }
        
        public ContentHandler getHandler() {
            return contentHandler_;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            currentLocator_ = locator;
        }

        @Override
        public void startDocument() throws SAXException {
            ++count_;
        }

        @Override
        public void endDocument() throws SAXException {
            currentLocator_ = null;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            contentHandler_.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            contentHandler_.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
            throws SAXException {
            contentHandler_.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            contentHandler_.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            contentHandler_.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            contentHandler_.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            contentHandler_.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            contentHandler_.skippedEntity(name);
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            lexicalHandler_.startDTD(name, publicId, systemId);
        }

        @Override
        public void endDTD() throws SAXException {
            lexicalHandler_.endDTD();
        }

        @Override
        public void startEntity(String name) throws SAXException {
            lexicalHandler_.startEntity(name);
        }

        @Override
        public void endEntity(String name) throws SAXException {
            lexicalHandler_.endEntity(name);
        }

        @Override
        public void startCDATA() throws SAXException {
            lexicalHandler_.startCDATA();
        }

        @Override
        public void endCDATA() throws SAXException {
            lexicalHandler_.endCDATA();
        }

        @Override
        public void comment(char[] ch, int start, int length) throws SAXException {
            lexicalHandler_.comment(ch, start, length);
        }

        @Override
        public String getPublicId() {
            if (currentLocator_ != null) {
                return currentLocator_.getPublicId();
            } else {
                return null;                
            }
        }

        @Override
        public String getSystemId() {
            if (currentLocator_ != null) {
                return currentLocator_.getSystemId();
            } else {
                return null;                
            }
        }

        @Override
        public int getLineNumber() {
            if (currentLocator_ != null) {
                return currentLocator_.getLineNumber();
            } else {
                return -1;                
            }
        }

        @Override
        public int getColumnNumber() {
            if (currentLocator_ != null) {
                return currentLocator_.getColumnNumber();
            } else {
                return -1;                
            }
        }
    }
}
