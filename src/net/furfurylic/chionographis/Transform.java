/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.w3c.dom.Document;
import org.xml.sax.ContentHandler;

/**
 * An <i>Transform</i> filter transforms each source document into an document
 * styled by an XSLT stylesheet.
 */
public final class Transform extends Sink implements Driver {
    private static final NetResourceCache<Templates> STYLESHEETS = new NetResourceCache<>();

    private Sinks sinks_;
    private String style_ = null;
    private boolean usesCache_ = true;
    private boolean force_ = false;
    private List<Param> params_ = Collections.emptyList();

    private URI styleURI_;
    private Map<String, Object> paramMap_ = null;

    private Stylesheet stylesheet_ = new Stylesheet();

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

    /**
     * Sets whether external resources referred through the transformation process
     * should be cached. Defaulted to "yes".
     *
     * <p>The external resources are:</p>
     * <ul>
     * <li>XSLT stylesheet files included by {@code xsl:include},</li>
     * <li>XSLT stylesheet files imported by {@code xsl:import},</li>
     * <li>external documents opened by {@code document} XPath functions,</li>
     * <li>and external parsed entities referred by documents above.</li>
     * </ul>
     *
     * @param cache
     *      {@code true} if cached; {@code false} otherwise.
     */
    public void setCache(boolean cache) {
        usesCache_ = cache;
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
        Param param = new Param(sinks_);
        if (params_.isEmpty()) {
            params_ = Collections.singletonList(param);
        } else {
            if (params_.size() == 1) {
                Param first = params_.get(0);
                params_ = new ArrayList<>();
                params_.add(first);
            }
            params_.add(param);
        }
        return param;
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
    void init(File baseDir, NamespaceContext namespaceContext, boolean force) {
        setUpParamMap(namespaceContext);

        styleURI_ = URI.create(style_);
        if (!styleURI_.isAbsolute()) {
            styleURI_ = baseDir.toPath().resolve(style_).toUri();
        }

        force_ = force_ || force;

        sinks_.init(baseDir, namespaceContext, force_);
    }

    private void setUpParamMap(NamespaceContext namespaceContext) {
        if (params_.isEmpty()) {
            paramMap_ = Collections.emptyMap();
            return;
        }
        if (params_.size() == 1) {
            Map.Entry<String, Object> entry = params_.get(0).yield(namespaceContext);
            sinks_.log(this, "Adding a stylesheet parameter: " + entry, Logger.Level.DEBUG);
            paramMap_ = Collections.singletonMap(entry.getKey(), entry.getValue());
            return;
        }

        paramMap_ = new TreeMap<>();
        for (Param param : params_) {
            Map.Entry<String, Object> entry = param.yield(namespaceContext);
            sinks_.log(this, "Adding a stylesheet parameter: " + entry, Logger.Level.DEBUG);
            if (paramMap_.put(entry.getKey(), entry.getValue()) != null) {
                sinks_.log(this,
                    "Stylesheet parameter named " + entry.getKey() + " added twice", Logger.Level.ERR);
                throw new BuildException();
            }
        }
    }

    @Override
    boolean[] preexamineBundle(String[] originalSrcFileNames, long[] originalSrcLastModifiedTimes) {
        if (!force_) {
            long styleLastModified = lastModified(styleURI_);
            if (styleLastModified > 0) {
                long[] lastModifiedTimes =
                    Arrays.stream(originalSrcLastModifiedTimes)
                          .map(l -> (l <= 0 ? l : Math.max(l, styleLastModified)))
                          .toArray();
                return sinks_.preexamineBundle(originalSrcFileNames, lastModifiedTimes);
            }
        }

        boolean[] includes = new boolean[originalSrcFileNames.length];
        Arrays.fill(includes, true);
        return includes;
    }

    private static long lastModified(URI uri) {
        if (uri.getScheme().equalsIgnoreCase("file")) {
            return new File(uri).lastModified();
        } else {
            return 0;
        }
    }

    @Override
    void startBundle() {
        sinks_.startBundle();
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName,
            long originalSrcLastModifiedTime, List<String> notUsed) {
        long styleLastModified = lastModified(styleURI_);
        long lastModified = (styleLastModified <= 0) ?
            styleLastModified : Math.max(originalSrcLastModifiedTime, styleLastModified);

        try {
            List<XPathExpression> referents = sinks_.referents();
            if (!referents.isEmpty()) {
                sinks_.log(this, "  Referral to the source contents required", Logger.Level.DEBUG);
                Document document;
                DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                dbfac.setNamespaceAware(true);
                try {
                    document = dbfac.newDocumentBuilder().newDocument();
                } catch (ParserConfigurationException e) {
                    throw new BuildException(e);
                }
                return new FinisherDOMResult(document,
                    () -> {
                        List<String> referredContents = Referral.extract(document, referents);
                        sinks_.log(this, "Referred source data: "
                            + String.join(", ", referredContents), Logger.Level.DEBUG);
                        Result openedResult =
                            sinks_.startOne(originalSrcIndex, originalSrcFileName,
                                lastModified, referredContents);
                        if (openedResult != null) {
                            try {
                                stylesheet_.newTransformer()
                                    .transform(new DOMSource(document), openedResult);
                            } catch (TransformerException e) {
                                throw new BuildException(e);
                            }
                            sinks_.finishOne(openedResult);
                        }
                    },
                    () -> {});
            } else {
                Result openedResult =
                    sinks_.startOne(originalSrcIndex, originalSrcFileName,
                        lastModified, Collections.emptyList());
                if (openedResult != null) {
                    TransformerHandler styler = stylesheet_.newTransformerHandler();
                    styler.setResult(openedResult);
                    return new FinisherSAXResult(styler,
                        () -> sinks_.finishOne(openedResult),
                        () -> sinks_.abortOne(openedResult));
                } else {
                    return null;
                }
            }
        } catch (TransformerConfigurationException e) {
            throw new FatalityException(e);
        }
    }

    @Override
    void finishOne(Result result) {
        assert result != null;
        assert result instanceof Finisher;
        ((Finisher) result).finish();
    }

    @Override
    void abortOne(Result result) {
        assert result != null;
        assert result instanceof Finisher;
        ((Finisher) result).abort();
    }

    @Override
    void finishBundle() {
        sinks_.finishBundle();
    }

    private static interface Finisher {
        void finish();
        void abort();
    }

    private static class FinisherSAXResult extends SAXResult implements Finisher {
        private Runnable finisher_;
        private Runnable aborter_;

        public FinisherSAXResult(ContentHandler handler, Runnable finisher, Runnable aborter) {
            super(handler);
            finisher_ = finisher;
            aborter_ = aborter;
        }

        @Override
        public void finish() {
            finisher_.run();
        }

        @Override
        public void abort() {
            aborter_.run();
        }
    }

    private static class FinisherDOMResult extends DOMResult implements Finisher {
        private Runnable finisher_;
        private Runnable aborter_;

        public FinisherDOMResult(Document document, Runnable finisher, Runnable aborter) {
            super(document);
            finisher_ = finisher;
            aborter_ = aborter;
        }

        @Override
        public void finish() {
            finisher_.run();
        }

        @Override
        public void abort() {
            aborter_.run();
        }
    }

    private class Stylesheet {
        private final Object LOCK = new Object();

        private SAXTransformerFactory tfac_ = null;
        private CachingResolver resolver_ = null;

        public Transformer newTransformer() throws TransformerConfigurationException {
            Templates compiledStylesheet = getCompiledStylesheet();
            Transformer transformer = compiledStylesheet.newTransformer();
            configureTransformer(transformer);
            return transformer;
        }

        public TransformerHandler newTransformerHandler() throws TransformerConfigurationException {
            Templates compiledStylesheet = getCompiledStylesheet();
            TransformerHandler styler;
            synchronized (LOCK) {
                if (tfac_ == null) {
                    tfac_ = (SAXTransformerFactory) TransformerFactory.newInstance();
                }
                styler = tfac_.newTransformerHandler(compiledStylesheet);
            }
            configureTransformer(styler.getTransformer());
            return styler;
        }

        private Templates getCompiledStylesheet() {
            return STYLESHEETS.get(
                styleURI_,
                u -> {},
                u -> {
                    sinks_.log(Transform.this,
                        "Reusing compiled stylesheet: " + u.toString(), Logger.Level.DEBUG);
                },
                this::compileStylesheet);
        }

        private Templates compileStylesheet(URI styleURI) {
            String styleSystemID = styleURI.toString();
            sinks_.log(Transform.this,
                "Compiling stylesheet: " + styleSystemID, Logger.Level.VERBOSE);
            synchronized (LOCK) {
                tfac_ = (SAXTransformerFactory) TransformerFactory.newInstance();
                if (usesCache_) {
                    resolver_ = new CachingResolver(
                        r -> sinks_.log(Transform.this, "Caching " + r, Logger.Level.DEBUG),
                        r -> sinks_.log(Transform.this, "Reusing " + r, Logger.Level.DEBUG));
                    tfac_.setURIResolver(resolver_);
                }
                try {
                    return tfac_.newTemplates(new StreamSource(styleSystemID));
                } catch (TransformerConfigurationException e) {
                    throw new FatalityException(e);
                }
            }
        }

        private void configureTransformer(Transformer transformer) {
            for (Map.Entry<String, Object> param : paramMap_.entrySet()) {
                transformer.setParameter(param.getKey(), param.getValue());
            }
            if (usesCache_) {
                transformer.setURIResolver(resolver_);
            }
        }
    }
}
