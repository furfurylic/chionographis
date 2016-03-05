/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.w3c.dom.Document;

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
    private CachingResolver resolver_;
    private Map<String, Object> params_;

    private Runnable finisher_;
    
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

            Result openedResult = sinks_.startOne(originalSrcIndex, originalSrcFileName);
            List<XPathExpression> referents = sinks_.referents();
            if (!referents.isEmpty()) {
                sinks_.log(this, "  Referral to the source contents required", LogLevel.DEBUG);
                Document document;
                DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                dbfac.setNamespaceAware(true);
                try {
                    document = dbfac.newDocumentBuilder().newDocument();
                } catch (ParserConfigurationException e) {
                    throw new BuildException(e);
                }
                Transformer transformer = stylesheet_.newTransformer();
                configureTransformer(transformer);
                finisher_ = () -> {
                    try {
                        transformer.transform(new DOMSource(document), openedResult);
                    } catch (TransformerException e) {
                        throw new BuildException(e);
                    }
                    List<String> referredContents = Referral.extract(document, referents);
                    sinks_.log(this, "  Referred source data: "
                        + Referral.join(referredContents), LogLevel.DEBUG);
                    sinks_.finishOne(referredContents);
                };
                return new DOMResult(document);
            } else {
                sinks_.log(this, "  Referral to the source contents not required", LogLevel.DEBUG);
                TransformerHandler styler = tfac_.newTransformerHandler(stylesheet_);
                configureTransformer(styler.getTransformer());
                styler.setResult(openedResult);
                finisher_ = () -> sinks_.finishOne(Collections.<String>emptyList());
                return new SAXResult(styler);
            }
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
            throw new BuildException(e);
        }
    }

    private void configureTransformer(Transformer transformer) {
        for (Map.Entry<String, Object> param : params_.entrySet()) {
            transformer.setParameter(param.getKey(), param.getValue());
        }
        if (usesCache_) {
            transformer.setURIResolver(resolver_);
        }
    }

    @Override
    void finishOne(List<String> notUsed) {
        finisher_.run();
    }

    @Override
    void abortOne() {
        sinks_.abortOne();
    }

    @Override
    void finishBundle() {
        sinks_.finishBundle();
    }
}
