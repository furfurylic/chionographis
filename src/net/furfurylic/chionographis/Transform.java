/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

/**
 * An <i>Transform</i> {@linkplain Sink sink}/{@linkplain SinkDriver sink driver} transforms
 * each source document into an document styled by an XSLT stylsheet.
 */
public final class Transform extends Sink implements SinkDriver {

    private Sinks sinks_;
    private String style_;
    private boolean usesCache_;
    private int paramCount_;

    private SAXTransformerFactory tfac_;
    private URI styleURI_;

    private Templates stylesheet_;
    private Map<String, Object> params_;
    private URIResolver resolver_;

    private String output_;
    
    Transform(Logger logger) {
        sinks_ = new Sinks(logger);
        style_ = null;
        usesCache_ = false;
        paramCount_ = 0;
        params_ = Collections.<String, Object>emptyMap();
    }
    
    /**
     * Sets the URI of the XSLT stylesheet. If the given string represents a relative URI,
     * it is resolved by {@linkplain Chionographis#setBaseDir(String)
     * the base directory of the task} to a file path.
     * 
     * @param style
     *      the URI of the XSLT stylesheet.
     */
    public void setStyle(String style) {
        style_ = style;
    }
    // TODO: Make this class able to accept non-file stylesheet URI
    
    public void setCache(boolean cache) {
        usesCache_ = cache;
    }
    
    /**
     * Adds a stylesheet parameter.
     * 
     * @return
     *      an empty stylesheet parameter.
     */
    public Param createParam() {
        ++paramCount_;
        return new Param(this::receiveParam);
    }
    
    private void receiveParam(String name, Object value) {
        if (params_.isEmpty()) {
            params_ = new TreeMap<>();
        }
        if (params_.put(name, value) != null) {
            sinks_.log(this, "Parameter " + name + " added twice", LogLevel.ERR);
            throw new BuildException(); // TODO: message
        }
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
        examineParams(namespaceContext);
                
        styleURI_ = URI.create(style_);
        if (!styleURI_.isAbsolute()) {
            styleURI_ = baseDir.toPath().resolve(style_).toUri();
        }
        sinks_.init(baseDir, namespaceContext);
    }

    private void examineParams(NamespaceContext namespaceContext) {
        int badParamCount = paramCount_ - params_.size();
        if (badParamCount > 0) {
            sinks_.log(this,
                badParamCount + " parameters left not fully configured", LogLevel.ERR);
            Optional<String> paramNames = params_.entrySet().stream()
                                            .filter(e -> e.getValue() == null)
                                            .map(e -> e.getKey())
                                            .reduce((r, s) -> r += ", " + s);
            if (paramNames.isPresent()) {
                sinks_.log(this,
                    "  Parameters without values are: " + paramNames.get(), LogLevel.ERR);
            }
            throw new BuildException(); // TODO: message
        }
        Map<String, Object> resolvedParams_ = new TreeMap<>();
        for (Map.Entry<String, Object> e : params_.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith("{")) {
                int indexOfColon = name.indexOf(':');
                if (indexOfColon != -1) {
                    String prefix = name.substring(0, indexOfColon);
                    String localName = name.substring(indexOfColon + 1);
                    String namespaceURI = namespaceContext.getNamespaceURI(prefix);
                    name = '{' + namespaceURI + '}' + localName;
                }
            }
            if (resolvedParams_.put(name, e.getValue()) != null) {
                sinks_.log(this, "Parameter " + name + " added twice", LogLevel.ERR);
                throw new BuildException(); // TODO: message                
            }
            sinks_.log(this, "Parameter added: " + name + '=' + e.getValue(), LogLevel.VERBOSE);            
        }
        params_ = resolvedParams_;
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> additionalURIs) {
        if (additionalURIs.isEmpty()) {
            additionalURIs = Collections.<URI>singleton(styleURI_);
        } else {
            additionalURIs = new HashSet<>(additionalURIs);
            additionalURIs.add(styleURI_);
            additionalURIs = Collections.unmodifiableSet(additionalURIs); 
        }
        return sinks_.preexamineBundle(originalSrcURIs, originalSrcFileNames, additionalURIs);
    }

    @Override
    void startBundle() {
        sinks_.startBundle();
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName) {
        output_ = null;        
        try {
            if (stylesheet_ == null) {
                tfac_ = (SAXTransformerFactory) TransformerFactory.newInstance();
                String styleSystemID = styleURI_.toString();
                sinks_.log(this, "Compiling stylesheet: " + styleSystemID, LogLevel.VERBOSE);
                if (usesCache_) {
                    resolver_ = new CachingResolver(
                        u -> sinks_.log(this, "Caching " + u, LogLevel.DEBUG),
                        u -> sinks_.log(this, "Reusing " + u, LogLevel.DEBUG));
                    tfac_.setURIResolver(resolver_);
                }
                stylesheet_ = tfac_.newTemplates(new StreamSource(styleSystemID));
            }
            TransformerHandler styler = tfac_.newTransformerHandler(stylesheet_);
            for (Map.Entry<String, Object> param : params_.entrySet()) {
                styler.getTransformer().setParameter(param.getKey(), param.getValue());
            }
            if (usesCache_) {
                styler.getTransformer().setURIResolver(resolver_);
            }
            styler.setResult(sinks_.startOne(originalSrcIndex, originalSrcFileName));
            ContentHandler handler;
            if (sinks_.needsOutput()) {
                sinks_.log(this, "  PI search required", LogLevel.DEBUG);
                handler = new OutputFinderHandler(styler, s -> output_ = s);
            } else {
                sinks_.log(this, "  PI search not required", LogLevel.DEBUG);
                handler = styler;
            }
            SAXResult result = new SAXResult(handler);
            result.setLexicalHandler(styler);
            return result;
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
            throw new BuildException(e);
        }
    }

    @Override
    void finishOne(String notUsed) {
        if (sinks_.needsOutput()) {
            sinks_.log(this, "  PI data is " + output_, LogLevel.DEBUG);
        }
        sinks_.finishOne(output_);
    }

    @Override
    void abortOne() {
        sinks_.abortOne();
    }

    @Override
    void finishBundle() {
        sinks_.finishBundle();
    }

    private static class OutputFinderHandler implements ContentHandler {
        
        private ContentHandler handler_;
        private Consumer<String> outputHandler_;
        private int counter_;

        public OutputFinderHandler(ContentHandler handler, Consumer<String> outputHandler) {
            handler_ = handler;
            outputHandler_ = outputHandler;
            counter_ = 0;
        }
        
        @Override
        public void setDocumentLocator(Locator locator) {
            handler_.setDocumentLocator(locator);
        }

        @Override
        public void startDocument() throws SAXException {
            handler_.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            handler_.endDocument();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            handler_.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            handler_.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            ++counter_;
            handler_.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            handler_.endElement(uri, localName, qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            handler_.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            handler_.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            if ((counter_ == 1) && target.equals("chionographis-output")) {
                outputHandler_.accept(data);
                ++counter_;
            } else {
                handler_.processingInstruction(target, data);                                
            }
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            handler_.skippedEntity(name);
        }
    }

    private static class CachingResolver implements URIResolver {

        Map<URI, Source> sources_;
        Consumer<URI> listenStored_;
        Consumer<URI> listenHit_;
        
        public CachingResolver(Consumer<URI> listenStored, Consumer<URI> listenHit) {
            listenStored_ = listenStored;
            listenHit_ = listenHit;
        }

        @Override
        public Source resolve(String href, String base) throws TransformerException {
            if (sources_ == null) {
                sources_ = new HashMap<>();
            }
            URI uri = URI.create(base).resolve(href).normalize();
            Source cached = sources_.get(uri);
            if (cached == null) {
                if (!sources_.containsKey(uri)) {
                    DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                    dbfac.setNamespaceAware(true);
                    try {
                        DocumentBuilder builder = dbfac.newDocumentBuilder();
                        Document document = builder.parse(uri.toString());
                        cached = new DOMSource(document, uri.toString());
                        listenStored_.accept(uri);
                    } catch (ParserConfigurationException | SAXException | IOException e) {
                    }
                    sources_.put(uri, cached);
                }
            } else {
                listenHit_.accept(uri);
            }
            return cached;
        }
        
    }
}
