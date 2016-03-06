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
    private String style_ = null;
    private boolean usesCache_ = false;
    private boolean force_ = false;
    private int paramCount_ = 0;

    private SAXTransformerFactory tfac_;
    private URI styleURI_;

    private Templates stylesheet_;
    private CachingResolver resolver_;
    private Map<String, Object> params_ = Collections.emptyMap();

    private Runnable finisher_;

    Transform(Logger logger) {
        sinks_ = new Sinks(logger);
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

    public void setForce(boolean force) {
        force_ = force;
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
        if (name.isEmpty()) {
            sinks_.log(this, "Parameters with empty names are not acceptable", LogLevel.ERR);
            throw new BuildException();
        }
        if (params_.isEmpty()) {
            params_ = new TreeMap<>();
        }
        if (params_.put(name, value) != null) {
            sinks_.log(this, "Parameter " + name + " added twice", LogLevel.ERR);
            throw new BuildException();
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
            throw new BuildException();
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
                throw new BuildException();
            }
            sinks_.log(this, "Parameter added: " + name + '=' + e.getValue(), LogLevel.VERBOSE);
        }
        params_ = resolvedParams_;
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> stylesheetURIs) {
        if (force_) {
            boolean[] result = new boolean[originalSrcURIs.length];
            Arrays.fill(result, true);
            return result;
        }

        if (stylesheetURIs.isEmpty()) {
            stylesheetURIs = Collections.<URI>singleton(styleURI_);
        } else {
            stylesheetURIs = new HashSet<>(stylesheetURIs);
            stylesheetURIs.add(styleURI_);
            stylesheetURIs = Collections.unmodifiableSet(stylesheetURIs);
        }
        return sinks_.preexamineBundle(originalSrcURIs, originalSrcFileNames, stylesheetURIs);
    }

    @Override
    void startBundle() {
        sinks_.startBundle();
    }

    @Override
    Result startOne(int originalSrcIndex, URI originalSrcURI, String originalSrcFileName,
            List<String> notUsed) {
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

            List<XPathExpression> referents =
                sinks_.referents(originalSrcIndex, originalSrcURI, originalSrcFileName);
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
                    List<String> referredContents = Referral.extract(document, referents);
                    sinks_.log(this, "  Referred source data: "
                        + String.join(", ", referredContents), LogLevel.DEBUG);
                    Result openedResult =
                        sinks_.startOne(originalSrcIndex, originalSrcURI, originalSrcFileName, referredContents);
                    if (openedResult != null) {
                        try {
                            transformer.transform(new DOMSource(document), openedResult);
                        } catch (TransformerException e) {
                            throw new BuildException(e);
                        }
                        sinks_.finishOne();
                    }
                };
                return new DOMResult(document);
            } else {
                sinks_.log(this, "  Referral to the source contents not required", LogLevel.DEBUG);
                Result openedResult =
                    sinks_.startOne(originalSrcIndex, originalSrcURI, originalSrcFileName, Collections.emptyList());
                if (openedResult != null) {
                    TransformerHandler styler = tfac_.newTransformerHandler(stylesheet_);
                    configureTransformer(styler.getTransformer());
                    styler.setResult(openedResult);
                    finisher_ = () -> sinks_.finishOne();
                    return new SAXResult(styler);
                } else {
                    // TODO: then templates are not required to have been compiled
                    return null;
                }
            }
        } catch (TransformerConfigurationException e) {
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
    void finishOne() {
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
