/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.xml.namespace.NamespaceContext;
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
import org.xml.sax.ContentHandler;

import net.furfurylic.chionographis.Logger.Level;

/**
 * An <i>Transform</i> filter transforms each source document into an document
 * styled by an XSLT stylesheet.
 */
public final class Transform extends Filter {
    private static final NetResourceCache<Templates> STYLESHEETS = new NetResourceCache<>();

    private String style_ = null;
    private boolean usesCache_ = true;
    private Assemblage<Param> params_ = new Assemblage<>();
    private Depends depends_ = null;

    private URI styleURI_;

    /**
     * The maximum last modified time of the stylesheet and the specified depended resources.
     * 0 means "unknown" or "very new".
     */
    private long lastModified_;

    private Map<String, Object> paramMap_ = null;

    private Stylesheet stylesheet_ = new Stylesheet();

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
    Transform(Logger logger, Function<String, String> expander,
            Consumer<BuildException> exceptionPoster) {
        super(logger, expander, exceptionPoster);
    }

    /**
     * Sets the URI or the file path of the XSLT stylesheet.
     * If the given string represents relative, it is resolved by
     * {@linkplain Chionographis#setBaseDir(String) the base directory of the task}
     * to a file path.
     *
     * @param style
     *      the URI or the file path of the XSLT stylesheet.
     */
    public void setStyle(String style) {
        style_ = style;
    }

    /**
     * Sets whether external resources referred through the transformation process
     * should be cached. Defaulted to {@code true}.
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
     * Adds a stylesheet parameter.
     *
     * @return
     *      an empty stylesheet parameter.
     */
    public Param createParam() {
        Param param = new Param(logger(), expander(), exceptionPoster());
        params_.add(param);
        return param;
    }

    /**
     * Adds an additional depended resources by this task.
     *
     * <p>The depended resources are simply used for the decision
     * whether the outputs are up to date.</p>
     *
     * @return
     *      an empty additional depended resource container object.
     */
    public Depends createDepends() {
        depends_ = new Depends(logger(), exceptionPoster());
        return depends_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun) {
        paramMap_ = createParamMap(namespaceContext);

        if (style_ == null) {
            logger().log(this, "\"style\" must be specified", Level.ERR);
            throw new BuildException();
        }
        try {
            // First we try as a URI
            styleURI_ = new URI(style_);
        } catch (URISyntaxException e) {
            // Second we try as a file
        }
        if ((styleURI_ == null) || !styleURI_.isAbsolute()) {
            styleURI_ = baseDir.toPath().resolve(style_).toUri();
        }

        sink().init(baseDir, namespaceContext, isForce(), dryRun);
    }

    private Map<String, Object> createParamMap(NamespaceContext namespaceContext) {
        return params_.toMap(p -> p.yield(namespaceContext),
            e -> logger().log(this,
                    "Adding a stylesheet parameter: " + e, Level.DEBUG),
            k -> {
                logger().log(this,
                    "Stylesheet parameter named " + k + " added twice", Level.ERR);
                throw new BuildException();
            });
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, long[] origSrcLastModTimes) {
        setUpLastModified();
        if ((!isForce()) && (lastModified_ != 0)) {
            long[] lastModifiedTimes =
                Arrays.stream(origSrcLastModTimes)
                      .map(l -> (l <= 0 ? l : Math.max(l, lastModified_)))
                      .toArray();
            return sink().preexamineBundle(origSrcFileNames, lastModifiedTimes);
        } else {
            boolean[] includes = new boolean[origSrcFileNames.length];
            Arrays.fill(includes, true);
            return includes;
        }
    }

    private void setUpLastModified() {
        if (styleURI_.getScheme().equalsIgnoreCase("file")) {
            lastModified_ = new File(styleURI_).lastModified();
            if (depends_ == null) {
                return;
            }
            if (lastModified_ > 0) {
                long dependsLastModified = depends_.lastModified();
                if (dependsLastModified != 0) {
                    lastModified_ = Math.max(lastModified_, dependsLastModified);
                    return;
                } else {
                    // fall through: 0 means "unknown"
                }
            }
        }
        lastModified_ = 0;  // "unknown" or "very new"
    }

    @Override
    void startBundle() {
        sink().startBundle();
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            long origSrcLastModTime, List<String> notUsed) {
        try {
            List<XPathExpression> referents = sink().referents();
            long lastModified = ((origSrcLastModTime <= 0) || (lastModified_ <= 0)) ?
                0 : Math.max(origSrcLastModTime, lastModified_);
            if (!referents.isEmpty()) {
                return new FinisherDOMResult(
                    r -> {
                        List<String> referredContents = Referral.extract(r.getNode(), referents);
                        logger().log(this, "Referred source data: "
                            + String.join(", ", referredContents), Level.DEBUG);
                        Result openedResult =
                            sink().startOne(origSrcIndex, origSrcFileName,
                                lastModified, referredContents);
                        if (openedResult != null) {
                            try {
                                stylesheet_.newTransformer()
                                    .transform(new DOMSource(r.getNode()), openedResult);
                            } catch (TransformerException e) {
                                throw new BuildException(e);
                            }
                            sink().finishOne(openedResult);
                        }
                    },
                    () -> {});
            } else {
                Result openedResult =
                    sink().startOne(origSrcIndex, origSrcFileName,
                        lastModified, Collections.emptyList());
                if (openedResult != null) {
                    TransformerHandler styler = stylesheet_.newTransformerHandler();
                    styler.setResult(openedResult);
                    return new FinisherSAXResult(styler,
                        () -> sink().finishOne(openedResult),
                        () -> sink().abortOne(openedResult));
                } else {
                    return null;
                }
            }
        } catch (TransformerConfigurationException e) {
            throw new BuildException(e);
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
        sink().finishBundle();
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
        private Consumer<DOMResult> finisher_;
        private Runnable aborter_;

        public FinisherDOMResult(Consumer<DOMResult> finisher, Runnable aborter) {
            super();
            finisher_ = finisher;
            aborter_ = aborter;
        }

        @Override
        public void finish() {
            finisher_.accept(this);
        }

        @Override
        public void abort() {
            aborter_.run();
        }
    }

    private class Stylesheet {
        private final Object LOCK = new Object();

        private SAXTransformerFactory tfac_ = null;

        public Transformer newTransformer() throws TransformerConfigurationException {
            Templates compiledStylesheet = getCompiledStylesheet();
            Transformer transformer = compiledStylesheet.newTransformer();
            configureTransformer(transformer);
            return transformer;
        }

        public TransformerHandler newTransformerHandler()
                throws TransformerConfigurationException {
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
                    logger().log(Transform.this,
                        "Reusing compiled stylesheet: " + u.toString(), Level.DEBUG);
                },
                this::compileStylesheet);
        }

        private Templates compileStylesheet(URI styleURI) {
            String styleSystemID = styleURI.toString();
            logger().log(Transform.this,
                "Compiling stylesheet: " + styleSystemID, Level.VERBOSE);
            synchronized (LOCK) {
                tfac_ = (SAXTransformerFactory) TransformerFactory.newInstance();
                if (usesCache_) {
                    tfac_.setURIResolver(newURIResolver());
                }
                try {
                    return tfac_.newTemplates(new StreamSource(styleSystemID));
                } catch (TransformerConfigurationException e) {
                    logger().log(Transform.this,
                        "Failed to compile stylesheet: " + styleSystemID, Level.ERR);
                    throw new BuildException(e);
                }
            }
        }

        private void configureTransformer(Transformer transformer) {
            for (Map.Entry<String, Object> param : paramMap_.entrySet()) {
                transformer.setParameter(param.getKey(), param.getValue());
            }
            if (usesCache_) {
                transformer.setURIResolver(newURIResolver());
            }
        }

        private CachingResolver newURIResolver() {
            return new CachingResolver(
                r -> logger().log(Transform.this, "Caching " + r, Level.DEBUG),
                r -> logger().log(Transform.this, "Reusing " + r, Level.DEBUG));
        }
    }
}
