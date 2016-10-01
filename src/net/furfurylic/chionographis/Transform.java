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
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;

import net.furfurylic.chionographis.Logger.Level;

/**
 * An <i>Transform</i> filter transforms each source document into an document
 * styled by an XSLT stylesheet.
 */
public final class Transform extends Filter {
    private static final NetResourceCache<Templates> STYLESHEETS = new NetResourceCache<>();

    private final ReentrantLock LOCK = new ReentrantLock();
    private SAXTransformerFactory tfac_ = null;

    private String style_ = null;
    private boolean usesCache_ = true;
    private Assemblage<Param> params_ = new Assemblage<>();
    private Depends depends_ = null;

    private Function<String, URI> getAbsoluteURI_;
    private StylesheetLocation stylesheetLocation_;

    private Map<String, Object> paramMap_ = null;

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     * @param propertyExpander
     *      an object which expands properties in a text, which shall not be {@code null}.
     * @param exceptionPoster
     *      an object which consumes exceptions occurred during the preparation process;
     *      which shall not be {@code null}.
     */
    Transform(Logger logger, Function<String, String> propertyExpander,
            Consumer<BuildException> exceptionPoster) {
        super(logger, propertyExpander, exceptionPoster);
    }

    /**
     * Sets the URI or the file path of the XSLT stylesheet.
     * If the given string represents relative, it is resolved by
     * {@linkplain Chionographis#setBaseDir(String) the base directory of the task}
     * to a file path.
     *
     * <p>If this attribute is omitted, this <i>Transform</i> filter gets the stylesheet location
     * through the <a href="https://www.w3.org/TR/xml-stylesheet/">"Associating Style Sheets with
     * XML documents"</a> mechanism.</p>
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
        Param param = new Param(logger(), propertyExpander(), exceptionPoster());
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

        Function<String, URI> getAbsoluteURI = s -> {
            URI uri = null;
            try {
                // First we try as a URI
                uri = new URI(s);
            } catch (URISyntaxException e) {
                // Second we try as a file
            }
            if ((uri == null) || !uri.isAbsolute()) {
                uri = baseDir.toPath().resolve(s).toUri();
            }
            return uri;
        };

        if (style_ != null) {
            getAbsoluteURI_ = null;
            stylesheetLocation_ = new StylesheetLocation(getAbsoluteURI.apply(style_), depends_);
        } else {
            getAbsoluteURI_ = getAbsoluteURI;
            stylesheetLocation_ = null;
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
        if ((!isForce()) && (stylesheetLocation_ != null)) {
            long[] lastModifiedTimes =
                Arrays.stream(origSrcLastModTimes)
                      .map(l -> stylesheetLocation_.mixLastModified(l))
                      .toArray();
            return sink().preexamineBundle(origSrcFileNames, lastModifiedTimes);
        } else {
            boolean[] includes = new boolean[origSrcFileNames.length];
            Arrays.fill(includes, true);
            return includes;
        }
    }

    @Override
    void startBundle() {
        sink().startBundle();
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            long origSrcLastModTime, List<String> notUsed) {
        List<XPathExpression> referents = sink().referents();
        long lastModified = ((origSrcLastModTime <= 0) || (stylesheetLocation_ == null)) ?
            // if stylesheet is not decided, use origSrcLastModTime only
            Math.max(0, origSrcLastModTime) :
            // otherwise use max of origSrcLastModTime and stylesheet's last mod
            stylesheetLocation_.mixLastModified(origSrcLastModTime);
        if (!referents.isEmpty() || (stylesheetLocation_ == null)) {
            return new FinisherDOMResult(origSrcIndex, origSrcFileName, lastModified);
        } else {
            Result openedResult =
                sink().startOne(origSrcIndex, origSrcFileName,
                    lastModified, Collections.emptyList());
            return (openedResult != null) ? new FinisherSAXResult(openedResult) : null;
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

    private class FinisherSAXResult extends SAXResult implements Finisher {
        private Result openedResult_;

        public FinisherSAXResult(Result openedResult) {
            openedResult_ = openedResult;
            TransformerHandler styler = newTransformerHandler();
            styler.setResult(openedResult_);
            setHandler(styler);
        }

        @Override
        public void finish() {
            sink().finishOne(openedResult_);
        }

        @Override
        public void abort() {
            sink().abortOne(openedResult_);
        }
    }

    private class FinisherDOMResult extends DOMResult implements Finisher {
        private int origSrcIndex_;
        private String origSrcFileName_;
        private long lastModified_;

        public FinisherDOMResult(int origSrcIndex, String origSrcFileName, long lastModified) {
            origSrcIndex_ = origSrcIndex;
            origSrcFileName_ = origSrcFileName;
            lastModified_ = lastModified;
        }

        @Override
        public void finish() {
            // Prepare referred contents if needed
            List<String> referredContents;
            if (!sink().referents().isEmpty()) {
                referredContents = Referral.extract(getNode(), sink().referents());
                logger().log(Transform.this, "Referred source data: "
                    + String.join(", ", referredContents), Level.DEBUG);
            } else {
                referredContents = Collections.emptyList();
            }

            // Try to open the sink's result and transfer the DOM node to it if opened
            Result openedResult = null;
            try {
                if (stylesheetLocation_ != null) {
                    // With a stylesheet fixed up-front
                    openedResult = sink().startOne(origSrcIndex_, origSrcFileName_,
                            lastModified_, referredContents);
                    if (openedResult != null) {
                        DOMSource source = new DOMSource(getNode(), getSystemId());
                        newTransformer().transform(source, openedResult);
                    } else {
                        return;
                    }
                } else {
                    // With a stylesheet associated with the source
                    DOMSource source = new DOMSource(getNode(), getSystemId());
                    Map.Entry<Long, Supplier<Transformer>> assoc =
                        extractAssociation(source, lastModified_);
                    openedResult = sink().startOne(origSrcIndex_, origSrcFileName_,
                            assoc.getKey().longValue(), referredContents);
                    if (openedResult != null) {
                        assoc.getValue().get().transform(source, openedResult);
                    } else {
                        return;
                    }
                }
            } catch (TransformerException e) {
                if (openedResult != null) {
                    sink().abortOne(openedResult);
                }
                if (stylesheetLocation_ != null) {
                    throw new BuildException(e);
                } else {
                    throw new NonfatalBuildException(e);
                }
            }

            // Close the sink's opened result.
            sink().finishOne(openedResult);
        }

        @Override
        public void abort() {
        }
    }

    private static class StylesheetLocation {
        private URI uri_;
        private long lastModified_;

        public StylesheetLocation(URI uri, Depends depends) {
            uri_ = uri;

            if (uri_.getScheme().equalsIgnoreCase("file")) {
                lastModified_ = new File(uri_).lastModified();
                if (depends == null) {
                    return;
                }
                if (lastModified_ > 0) {
                    long dependsLastModified = depends.lastModified();
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

        public URI uri() {
            return uri_;
        }

        /**
         * Gets maximum last modified time of the stylesheet and the specified other resource.
         *
         * @param otherLastModified
         *      the last modified time of the other resource,
         *      where 0 means "unknown" or "very new".
         *
         * @return
         *      0 if "unknown" (or "very new"), positive otherwise.
         */
        public long mixLastModified(long otherLastModified) {
            if (otherLastModified <= 0) {
                return otherLastModified;
            } else if (lastModified_ == 0) {
                return 0;
            } else {
                return Math.max(otherLastModified, lastModified_);
            }
        }
    }

    /**
     * Creates a transformer from the stylesheet pointed by {@link #stylesheetLocation_}
     * and configure it in terms of parameters and URI resolvers.
     *
     * @return
     *      the transformer, which shall not be {@code null}.
     *
     * @throws BuildException
     *      when a stylesheet compilation error occurs.
     */
    private Transformer newTransformer() {
        try {
            return configureTransformer(
                getCompiledStylesheet(stylesheetLocation_.uri(), null, true).newTransformer());
        } catch (TransformerConfigurationException e) {
            throw new BuildException(e);
        }
    }

    /**
     * Creates a transformer handler from the stylesheet pointed by {@link #stylesheetLocation_}
     * and configure it in terms of parameters and URI resolvers.
     *
     * @return
     *      the transformer handler, which shall not be {@code null}.
     *
     * @throws BuildException
     *      when a stylesheet compilation error occurs.
     */
    private TransformerHandler newTransformerHandler() {
        prepareTransformerFactory();
        TransformerHandler styler;
        LOCK.lock();
        try {
            styler = tfac_.newTransformerHandler(
                getCompiledStylesheet(stylesheetLocation_.uri(), null, true));
        } catch (TransformerConfigurationException e) {
            throw new BuildException(e);
        } finally {
            LOCK.unlock();
        }
        configureTransformer(styler.getTransformer());
        return styler;
    }

    /**
     * Reads the specified source and extract the associated stylesheet information.
     *
     * @param source
     *      a TrAX {@code Source} object which possibly contains an associated stylesheet
     *      information, which shall not be {@code null}.
     * @param lastModified
     *      the last modified time of {@code source}, where 0 means "unknown" (or "very new").
     *
     * @return
     *      a pair of the last modified time of the stylesheet (where 0 means "unknown")
     *      and a lazy initializer of a configured TrAX {@code Transformer} object,
     *      which shall not be {@code null}.
     *
     * @throws NonfatalBuildException
     *      when no associated stylesheet information found.
     */
    private Map.Entry<Long, Supplier<Transformer>> extractAssociation(
            Source source, long lastModified) {
        prepareTransformerFactory();

        // Get Source object of the stylesheet
        Source styleSource = getAssociatedStylesheet(source);
        if (styleSource == null) {
            logger().log(this,
                "Cannot get associated stylesheet information", Level.WARN);
            throw new NonfatalBuildException().setLogged();
        }

        // Get the system ID of the stylesheet
        String styleSystemID = null;
        if (styleSource instanceof SAXSource) {
            styleSystemID = ((SAXSource) styleSource).getInputSource().getSystemId();
        } else {
            styleSystemID = styleSource.getSystemId();
        }

        // If the system ID of the stylesheet is available,
        // we will compile it into a Template, otherwise we will compile it into a Transformer
        if (styleSystemID != null) {
            StylesheetLocation stylesheetLocation =
                new StylesheetLocation(getAbsoluteURI_.apply(styleSystemID), depends_);
            return new AbstractMap.SimpleEntry<Long, Supplier<Transformer>>(
                stylesheetLocation.mixLastModified(lastModified),
                () -> {
                    try {
                        return configureTransformer(
                            getCompiledStylesheet(stylesheetLocation.uri(), styleSource, false)
                                .newTransformer());
                    } catch (TransformerConfigurationException e) {
                        throw new NonfatalBuildException(e);
                    }
                });
        } else {
            return new AbstractMap.SimpleEntry<Long, Supplier<Transformer>>(
                0L,
                () -> {
                    return configureTransformer(compileStylesheet1(styleSource));
                });
        }
    }

    private Source getAssociatedStylesheet(Source source) {
        LOCK.lock();
        try {
            return tfac_.getAssociatedStylesheet(source, null, null, null);
        } catch (TransformerConfigurationException e) {
            logger().log(this,
                "Cannot get associated stylesheet information", Level.WARN);
            logger().log(Transform.this, e, "  Cause: ", Level.WARN, Level.VERBOSE);
            throw new NonfatalBuildException().setLogged();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Gets a TrAX {@code Templates} object possibly from the cache.
     *
     * @param styleURI
     *      the absolute URI of the stylesheet, which shall not be {@code null}.
     * @param styleSource
     *      the stylesheet in a TrAX {@code Source} object form, which can be {@code null}.
     * @param failsFatal
     *      {@code true} if stylesheet compilation errors should bring on a fatal situation;
     *      {@code false} otherwise.
     *
     * @return
     *      a TrAX {@code Templates} object for {@code styleURI} and possibly {@code styleSource}.
     *
     * @throws BuildException
     *      when a stylesheet compilation error occurs.
     */
    private Templates getCompiledStylesheet(
            URI styleURI, Source styleSource, boolean failsFatal) {
        return STYLESHEETS.get(
            styleURI,
            u -> {},
            u -> {
                logger().log(this,
                    "Reusing compiled stylesheet: " + u.toString(), Level.DEBUG);
            },
            u -> compileStylesheetN(
                    ((styleSource == null) ? new StreamSource(u.toString()) : styleSource),
                    failsFatal));
    }

    /**
     * Compiles an XSLT stylesheet to get a TrAX {@code Templates} object.
     *
     * <p>This method always tries to compile the stylesheet
     * (to be specific, does not refer the cache).</p>
     *
     * @param styleSource
     *      the stylesheet in a TrAX {@code Source} object form, which shall not be {@code null}.
     * @param failsFatal
     *      {@code true} if stylesheet compilation errors should bring on a fatal situation;
     *      {@code false} otherwise.
     *
     * @return
     *      a TrAX {@code Templates} object for {@code styleSource}.
     *
     * @throws BuildException
     *      when a stylesheet compilation error occurs.
     */
    private Templates compileStylesheetN(Source styleSource, boolean failsFatal) {
        assert(styleSource.getSystemId() != null);
        return compileStylesheet(styleSource,(f, s) -> {
            try {
                return f.newTemplates(s);
            } catch (TransformerConfigurationException e) {
                if (failsFatal) {
                    throw new BuildException(e);
                } else {
                    throw new NonfatalBuildException(e);
                }
            }
        });
    }

    /**
     * Compiles an XSLT stylesheet to get a TrAX {@code Transformer} object.
     *
     * <p>This method always tries to compile the stylesheet
     * (to be specific, does not refer the cache).</p>
     *
     * <p>This method fails in a nonfatal manner if a stylesheet compilation error occurs.</p>
     *
     * <p>The returned object is not yet configured in terms of stylesheet parameters and URI
     * resolvers.</p>
     *
     * @param styleSource
     *      the stylesheet in a TrAX {@code Source} object form, which shall not be {@code null}.
     *
     * @return
     *      a TrAX {@code Transformer} object for {@code styleSource}.
     *
     * @throws NonfatalBuildException
     *      when a stylesheet compilation error occurs.
     */
    private Transformer compileStylesheet1(Source styleSource) {
        assert (styleSource.getSystemId() == null);
        return compileStylesheet(styleSource,(f, s) -> {
            try {
                return f.newTransformer(s);
            } catch (TransformerConfigurationException e) {
                throw new NonfatalBuildException(e);
            }
        });
    }

    private <R> R compileStylesheet(
            Source style, BiFunction<SAXTransformerFactory, Source, R> f) {
        if (style.getSystemId() != null) {
            logger().log(this, "Compiling stylesheet: " + style.getSystemId(), Level.VERBOSE);
        } else {
            logger().log(this, "Compiling stylesheet", Level.VERBOSE);
        }
        prepareTransformerFactory();
        LOCK.lock();
        try {
            return f.apply(tfac_, style);
        } catch (BuildException e) {
            String log = (style.getSystemId() != null) ?
                "Failed to compile stylesheet: " + style.getSystemId() :
                "Failed to compile stylesheet";
            if (e instanceof NonfatalBuildException) {
                logger().log(this, log, Level.WARN);
                logger().log(this, e, "  Cause: ", Level.INFO, Level.VERBOSE);
                throw ((NonfatalBuildException) e).setLogged();
            } else {
                logger().log(this, log, Level.ERR);
                throw e;
            }
        } finally {
            LOCK.unlock();
        }
    }

    private Transformer configureTransformer(Transformer transformer) {
        for (Map.Entry<String, Object> param : paramMap_.entrySet()) {
            transformer.setParameter(param.getKey(), param.getValue());
        }
        if (usesCache_) {
            transformer.setURIResolver(newURIResolver());
        }
        return transformer;
    }

    private void prepareTransformerFactory() {
        LOCK.lock();
        try {
            if (tfac_ == null) {
                tfac_ = (SAXTransformerFactory) TransformerFactory.newInstance();
                if (usesCache_) {
                    tfac_.setURIResolver(newURIResolver());
                }
            }
        } finally {
            LOCK.unlock();
        }
    }

    private CachingResolver newURIResolver() {
        return new CachingResolver(
            r -> logger().log(this, "Caching " + r, Level.DEBUG),
            r -> logger().log(this, "Reusing " + r, Level.DEBUG));
    }
}
