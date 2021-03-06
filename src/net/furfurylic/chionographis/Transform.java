/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.XMLCatalog;
import org.apache.tools.ant.types.resources.URLResource;

import net.furfurylic.chionographis.Logger.Level;

/**
 * An <i>Transform</i> filter transforms each source document into an document
 * styled by an XSLT stylesheet.
 */
public final class Transform extends Filter {
    private static final NetResourceCache<Templates> STYLESHEETS = new NetResourceCache<>();

    private final ReentrantLock LOCK = new ReentrantLock();
    private SAXTransformerFactory tfac_ = null;
    private URIResolver resolver_ = null;

    private String style_ = null;
    private YesNo usesCache_ = YesNo.DEFAULT;
    private Optional<Assoc> assoc_ = Optional.empty();
    private Assemblage<Param> params_ = new Assemblage<>();
    private Depends depends_ = null;

    /**
     * A function which convert the stylesheet URI string into the real URI.
     * This field remains {@c null} forever if there is a stylesheet URI configured up-front.
     */
    private Function<String, URI> getAbsoluteURI_;

    /**
     * The stylesheet URI, which remains {@c null} forever if there is no stylesheet URI
     * configured up-front.
     */
    private StylesheetLocation stylesheetLocation_;

    private Map<String, Object> paramMap_ = null;

    /** Sole constructor. */
    Transform() {
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
        if (assoc_.isPresent()) {
            throw new BuildException(
                "Stylesheet URI and stylesheet association must be specified exclusively",
                getLocation());
        } else {
            style_ = style;
        }
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
     * <p>When this attribute is set to {@code true} explicitly, {@linkplain
     * Chionographis#addXMLCatalog(XMLCatalog) XML catalogs} will not be used in the
     * transformation process.</p>
     *
     * @param cache
     *      {@code true} if cached; {@code false} otherwise.
     */
    public void setCache(boolean cache) {
        usesCache_ = YesNo.valueOf(cache);
    }

    /**
     * Adds a narrowing information of the search of the associated stylesheet.
     *
     * <p>This information cannot be specified
     * when {@linkplain #setStyle(String) the URI of the stylesheet is explicitly specified}.</p>
     *
     * @return
     *      a narrowing information of the search of the associated stylesheet.
     *
     * @since 1.2
     */
    public Assoc createAssoc() {
        if (assoc_.isPresent()) {
            throw new BuildException("Stylesheet association specified twice", getLocation());
        } else if (style_ != null) {
            // According to Ant's manual, setStyle must occur prior to createAssoc.
            // But...
            throw new BuildException(
                "Stylesheet URI and stylesheet association must be specified exclusively",
                getLocation());
        }
        assoc_ = Optional.of(new Assoc());
        return assoc_.get();
    }

    /**
     * Adds a stylesheet parameter.
     *
     * @return
     *      an empty stylesheet parameter.
     */
    public Param createParam() {
        Param param = new Param();
        params_.add(param);
        return param;
    }

    /**
     * Adds a dependency spec between resources.
     *
     * <p>The depended resources are simply used for the decision
     * whether the outputs are up to date.</p>
     *
     * @return
     *      an empty object which instructs dependency between resources to this driver.
     */
    public Depends createDepends() {
        if (depends_ != null) {
            throw new BuildException("\"depends\" added twice", getLocation());
        }
        depends_ = new Depends();
        return depends_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun) {
        paramMap_ = createParamMap(namespaceContext);

        if (style_ != null) {
            getAbsoluteURI_ = null;
            URI absoluteURI = URIUtils.getAbsoluteURI(style_, baseDir);
            stylesheetLocation_ = new StylesheetLocation(absoluteURI, depends_, logger());
        } else {
            getAbsoluteURI_ = s -> URIUtils.getAbsoluteURI(s, baseDir);
            stylesheetLocation_ = null;
        }

        sink().init(baseDir, namespaceContext, xmlHelper(), logger(), isForce(), dryRun);
    }

    private Map<String, Object> createParamMap(NamespaceContext namespaceContext) {
        return params_.toMap(p -> p.yield(namespaceContext),
            e -> logger().log(this,
                    "Adding a stylesheet parameter: " + e, Level.DEBUG),
            k -> new BuildException(
                    "Stylesheet parameter named " + k + " added twice", getLocation()));
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, LongFunction<Resource>[] finders) {
        if ((!isForce()) && (stylesheetLocation_ != null)) {
            finders = finders.clone();
            for (int i = 0; i < finders.length; ++i) {
                finders[i] = stylesheetLocation_.mixFinder(finders[i]);
            }
            return sink().preexamineBundle(origSrcFileNames, finders);
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
            LongFunction<Resource> finder, List<String> notUsed) {
        List<XPathExpression> referents = sink().referents();
        if (stylesheetLocation_ != null) {
            // if stylesheet is decided, use max of origSrcLastModTime and stylesheet's last mod
            finder = stylesheetLocation_.mixFinder(finder);
        }   // otherwise use origSrcLastModTime only

        if (!referents.isEmpty() || (stylesheetLocation_ == null)) {
            return new FinisherDOMResult(origSrcIndex, origSrcFileName, finder);
        } else {
            Result openedResult =
                sink().startOne(origSrcIndex, origSrcFileName,
                        finder, Collections.emptyList());
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
    Sink abortOne(Result result) {
        assert result != null;
        assert result instanceof Finisher;
        ((Finisher) result).abort();
        return null;
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
            TransformerHandler handler = newTransformerHandler();
            handler.setResult(openedResult_);
            setHandler(handler);
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
        private LongFunction<Resource> finder_;

        public FinisherDOMResult(int origSrcIndex, String origSrcFileName,
                LongFunction<Resource> finder) {
            origSrcIndex_ = origSrcIndex;
            origSrcFileName_ = origSrcFileName;
            finder_ = finder;
        }

        @Override
        public void finish() {
            // Prepare referred contents if needed
            List<String> referredContents;
            if (!sink().referents().isEmpty()) {
                referredContents = XMLUtils.extract(getNode(), sink().referents());
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
                        finder_, referredContents);
                    if (openedResult != null) {
                        DOMSource source = new DOMSource(getNode(), getSystemId());
                        newTransformer().transform(source, openedResult);
                    } else {
                        return;
                    }
                } else {
                    // With a stylesheet associated with the source
                    DOMSource source = new DOMSource(getNode(), getSystemId());
                    Map.Entry<LongFunction<Resource>, Supplier<Transformer>> assoc =
                        extractAssociation(source, finder_);
                    openedResult = sink().startOne(origSrcIndex_, origSrcFileName_,
                        assoc.getKey(), referredContents);
                    if (openedResult == null) {
                        return;
                    }
                    assoc.getValue().get().transform(source, openedResult);
                }
            } catch (TransformerException e) {
                if (openedResult != null) {
                    sink().abortOne(openedResult);
                }
                if (stylesheetLocation_ != null) {
                    throw new BuildException(e, getLocation());
                } else {
                    throw new NonfatalBuildException(e, getLocation());
                }
            }

            // Close the sink's opened result.
            sink().finishOne(openedResult);
        }

        @Override
        public void abort() {
        }
    }

    /** A bundle of a stylesheet URI and its "newness". */
    private static class StylesheetLocation {
        private URI uri_;
        private LongFunction<Resource> finder_ = null;

        public StylesheetLocation(URI uri, Depends depends, Logger logger) {
            assert(uri != null);
            assert(logger != null);
            uri_ = uri;
            if (uri_.getScheme().equalsIgnoreCase("file")) {
                finder_ = ((depends != null) ? depends.detach(logger) : ReferencedSources.EMPTY)
                        .close(new File(uri_));
            }
        }

        /**
         * The URI of the stylesheet.
         *
         * @return
         *      the URI of the stylesheet, which shall not be {@code null}.
         */
        public URI uri() {
            return uri_;
        }

        /**
         * Mix the "newness" of the stylesheet itself and other resources.
         *
         * @param other
         *      the "newness" of the other resources, which receives milliseconds of epoch
         *      (hereinafter called <var>l</var>) and returns a resource whose last modified time
         *      is unknown or newer than <var>l</var>; which shall not be {@code null}.
         *
         * @return
         *      the mixed "newness" of the stylesheet and the other resources, which shall not be
         *      {@code null}.
         */
        public LongFunction<Resource> mixFinder(LongFunction<Resource> other) {
            return l -> Stream.of(finder_, other)
                              .filter(f -> f != null)
                              .map(f -> f.apply(l))
                              .filter(f -> f != null)
                              .findAny()
                              .orElse(null);
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
    private Transformer newTransformer() throws BuildException {
        try {
            return configureTransformer(
                getCompiledStylesheet(stylesheetLocation_.uri(), null, true).newTransformer());
        } catch (TransformerConfigurationException e) {
            throw failureOnDetachingStylesheet(e, false);
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
    private TransformerHandler newTransformerHandler() throws BuildException {
        prepareTransformerFactory();
        TransformerHandler handler;
        LOCK.lock();
        try {
            handler = tfac_.newTransformerHandler(
                getCompiledStylesheet(stylesheetLocation_.uri(), null, true));
        } catch (TransformerConfigurationException e) {
            throw failureOnDetachingStylesheet(e, true);
        } finally {
            LOCK.unlock();
        }
        configureTransformer(handler.getTransformer());
        return handler;
    }

    /**
     * Reads the specified source and extract the associated stylesheet information.
     *
     * @param source
     *      a TrAX {@code Source} object which possibly contains an associated stylesheet
     *      information, which shall not be {@code null}.
     * @param finder
     *      the last modified time of {@code source}, where 0 means "unknown" (or "very new").
     *
     * @return
     *      a pair of the "newness" of the stylesheet and a lazy initializer of a configured TrAX
     *      {@code Transformer} object; neither which itself, whose key nor value shall not be
     *      {@code null}.
     *
     * @throws NonfatalBuildException
     *      when no associated stylesheet information found.
     */
    private Map.Entry<LongFunction<Resource>, Supplier<Transformer>> extractAssociation(
            Source source, LongFunction<Resource> finder) throws NonfatalBuildException {
        prepareTransformerFactory();

        // Get Source object of the stylesheet
        Source styleSource = getAssociatedStylesheetSource(source);
        if (styleSource == null) {
            throw new NonfatalBuildException(
                "Cannot get associated stylesheet information", getLocation());
        }

        // Get the system ID of the stylesheet
        String styleSystemID = (styleSource instanceof SAXSource) ?
            ((SAXSource) styleSource).getInputSource().getSystemId() :
            styleSource.getSystemId();

        // If the system ID of the stylesheet is available,
        // we will compile it into a Template, otherwise we will compile it into a Transformer
        // (Some processors set the source's system ID itself to the associated stylesheet's
        //  system ID, and this is very harmful to the caching behaviour,
        //  so we evade caching then)
        if ((styleSystemID != null) && !styleSystemID.equals(source.getSystemId())) {
            StylesheetLocation stylesheetLocation = new StylesheetLocation(
                    getAbsoluteURI_.apply(styleSystemID), depends_, logger());
            return new AbstractMap.SimpleEntry<LongFunction<Resource>, Supplier<Transformer>>(
                stylesheetLocation.mixFinder(finder),
                () -> {
                    try {
                        return configureTransformer(
                            getCompiledStylesheet(stylesheetLocation.uri(), styleSource, false)
                                .newTransformer());
                    } catch (TransformerConfigurationException e) {
                        throw failureOnDetachingStylesheet(e, false);
                    }
                });
        } else {
            return new AbstractMap.SimpleEntry<LongFunction<Resource>, Supplier<Transformer>>(
                l -> new URLResource(styleSystemID),
                () -> configureTransformer(compileStylesheet1(styleSource)));
        }
    }

    private Source getAssociatedStylesheetSource(Source source) throws NonfatalBuildException {
        LOCK.lock();
        try {
            return tfac_.getAssociatedStylesheet(
                source,
                assoc_.map(Assoc::getMedia).orElse(null),
                assoc_.map(Assoc::getTitle).orElse(null),
                assoc_.map(Assoc::getCharset).orElse(null));
        } catch (TransformerConfigurationException e) {
            throw new NonfatalBuildException(
                "Cannot get associated stylesheet information", e, getLocation());
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Gets a TrAX {@code Templates} object possibly from the cache.
     *
     * @param uri
     *      the absolute URI of the stylesheet, which shall not be {@code null}.
     * @param source
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
    private Templates getCompiledStylesheet(URI uri, Source source, boolean failsFatal)
            throws BuildException {
        assert(uri != null);
        return STYLESHEETS.get(
            uri,
            u -> {},
            u -> {
                logger().log(this,
                    "Reusing compiled stylesheet: " + u.toString(), Level.DEBUG);
            },
            u -> compileStylesheetN(
                    ((source == null) ? new StreamSource(u.toString()) : source),
                    failsFatal));
    }

    /**
     * Compiles an XSLT stylesheet to get a TrAX {@code Templates} object.
     *
     * <p>This method always tries to compile the stylesheet
     * (to be specific, does not refer the cache).</p>
     *
     * @param source
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
    private Templates compileStylesheetN(Source source, boolean failsFatal) throws BuildException {
        assert(source.getSystemId() != null);
        return compileStylesheet(source, (f, s) -> {
            try {
                return f.newTemplates(s);
            } catch (TransformerConfigurationException e) {
                throw failureOnCompilation(source, e, failsFatal);
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
    private Transformer compileStylesheet1(Source styleSource) throws NonfatalBuildException {
        assert (styleSource.getSystemId() == null);
        return compileStylesheet(styleSource, (f, s) -> {
            try {
                return f.newTransformer(s);
            } catch (TransformerConfigurationException e) {
                throw failureOnCompilation(styleSource, e, false);
            }
        });
    }

    /**
     * Compiles a stylesheet into TrAX {@code Templates} or {@code Transformer}.
     * This method can be called simultaneously on one identical object safely.
     *
     * @param source
     *      the source of the stylesheet, which shall not be {@code null}.
     * @param compile
     *      a function which compiles the stylesheet, which shall not be {@code null}.
     *
     * @return
     *      the compiled stylesheet.
     */
    private <R> R compileStylesheet(
            Source source, BiFunction<SAXTransformerFactory, Source, R> compile) {
        if (source.getSystemId() != null) {
            logger().log(this, "Compiling stylesheet: " + source.getSystemId(), Level.VERBOSE);
        } else {
            logger().log(this, "Compiling stylesheet", Level.VERBOSE);
        }
        prepareTransformerFactory();
        LOCK.lock();
        try {
            return compile.apply(tfac_, source);
        } finally {
            LOCK.unlock();
        }
    }

    private BuildException failureOnCompilation(Source source,
            TransformerConfigurationException e, boolean failsFatal) throws BuildException {
        String message = (source.getSystemId() != null) ?
            "Failed to compile stylesheet: " + source.getSystemId() :
            "Failed to compile stylesheet";
        if (failsFatal) {
            return new BuildException(message, e, getLocation());
        } else {
            return new NonfatalBuildException(message, e, getLocation());
        }
    }

    private NonfatalBuildException failureOnDetachingStylesheet(
            TransformerConfigurationException e, boolean onHandler) throws NonfatalBuildException {
        String message = onHandler ?
            "Cannot make a transformer from the stylesheet" :
            "Cannot make a transformer handler from the stylesheet";
        throw new NonfatalBuildException(message, e, getLocation());
    }

    private Transformer configureTransformer(Transformer transformer) {
        for (Map.Entry<String, Object> param : paramMap_.entrySet()) {
            transformer.setParameter(param.getKey(), param.getValue());
        }
        transformer.setURIResolver(renewedURIResolver());
        return transformer;
    }

    private void prepareTransformerFactory() {
        LOCK.lock();
        try {
            if (tfac_ == null) {
                tfac_ = (SAXTransformerFactory) TransformerFactory.newInstance();
                resolver_ = xmlHelper().fallbackURIResolver();
                if ((usesCache_ == YesNo.YES)
                 || ((usesCache_ == YesNo.DEFAULT) && (resolver_ == null))) {
                    resolver_ = new CachingResolver(
                        r -> logger().log(this, "Caching " + r, Level.DEBUG),
                        r -> logger().log(this, "Reusing " + r, Level.DEBUG));
                }
                tfac_.setURIResolver(renewedURIResolver());
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Creates a new {@link URIResolver} which wraps {@link #resolver_}.
     *
     * To create a new resolver for each transformation may be required to avoid errors
     * in some XSLT processors.
     *
     * @return
     *      a new resolver which wraps {@link #resolver_}, which may be {@code null}.
     */
    private URIResolver renewedURIResolver() {
        return (resolver_ != null) ? (h, b) -> resolver_.resolve(h, b) : null;
    }
}
