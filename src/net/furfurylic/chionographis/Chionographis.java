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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.LogLevel;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * An Ant task class that performs cascading transformation to XML documents.
 * As of now, only files can be original sources of the processing.
 *
 * <p>An object of this class behaves as a <i>sink driver</i>.</p>
 */
public final class Chionographis extends MatchingTask implements SinkDriver {

    private Path srcDir_;
    private Path baseDir_;
    private boolean usesCache_ = false;
    private boolean force_ = false;
    private boolean verbose_ = false;
    private int prefixCount_ = 0;

    private Map<String, String> prefixMap_ = Collections.emptyMap();
    private List<Meta> metas_;
    private Sinks sinks_ = new Sinks(new ChionographisLogger());

    /**
     * Sole constructor.
     */
    public Chionographis() {
    }

    /**
     * Prepares to accept <i>sinks</i> and other configuration elements.
     */
    @Override
    public void init() {
    }

    /**
     * Sets the base directory of this task. If this is an relative path,
     * it is resolved by the project's base directory.
     *
     * <p>By default, the base directory is identical to the project's base directory.</p>
     *
     * @param baseDir
     *      the base directory of this task.
     */
    public void setBaseDir(String baseDir) {
        baseDir_ = Paths.get(baseDir);
    }

    /**
     * Sets the original source directory. If this is an relative path,
     * it is resolved by {@linkplain #setBaseDir(String) this task's base directory}.
     *
     * <p>By default, the original source directory is identical to {@linkplain
     * Chionographis#setBaseDir(String) this task's base directory}.</p>
     *
     * @param srcDir
     *      the original source directory.
     */
    public void setSrcDir(String srcDir) {
        srcDir_ = Paths.get(srcDir);
    }

    public void setCache(boolean cache) {
        usesCache_ = cache;
    }

    public void setForce(boolean force) {
        force_ = force;
    }

    public void setVerbose(boolean verbose) {
        verbose_ = verbose;
    }

    /**
     * Adds a prefix-namespace URI mapping entry to this task.
     *
     * <p>The mapping information is needed by <i>{@linkplain Snip}</i> sinks when the
     * XPaths of their criteria includes namespace prefixes. <i>{@linkplain All}</i> and
     * <i>{@linkplain Transform}</i> sinks also can refer this mapping information, however,
     * it is not mandatory (they can use {@code "{namespaceURI}localName"} notation).</p>
     *
     * @return
     *      an empty prefix-namespace URI mapping entry.
     *
     * @see Snip#setSelect(String)
     * @see All#setRoot(String)
     * @see Transform#createParam()
     * @see Param#setName(String)
     */
    public Namespace createNamespace() {
        ++prefixCount_;
        return new Namespace(this::receiveNamespace);
    }

    /**
     * Receives a possibly imperfect prefix-namespace mapping entry.
     *
     * <p>This method is invoked by {@link Namespace#setPrefix(String)}.</p>
     *
     * <p>One prefix can be invoked twice at most, and then in the first call <i>namespaceURI</i>
     * must be {@code null}.</p>
     *
     * @param prefix
     *      the namespace prefix, which shall not be {@code null}.
     * @param namespaceURI
     *      the namespace URI, which may be {@code null}.
     */
    private void receiveNamespace(String prefix, String namespaceURI) {
        if (prefix.isEmpty()) {
            sinks_.log(this, "Empty namespace prefixes are not acceptable", LogLevel.ERR);
            throw new BuildException();
        }
        if ((prefix.length() >= 3) && prefix.substring(0, 3).equalsIgnoreCase("xml")) {
            sinks_.log(this, "Bad namespace prefix: " + prefix, LogLevel.ERR);
            throw new BuildException();
        }
        if (prefixMap_.isEmpty()) {
            prefixMap_ = new HashMap<>();
        }
        if (prefixMap_.put(prefix, namespaceURI) != null) {
            sinks_.log(this, "Namespace prefix " + prefix + " mapped twice", LogLevel.ERR);
            throw new BuildException();
        }
        if (namespaceURI != null) {
            sinks_.log(this,
                "Namespace prefix added: " + prefix + '=' + namespaceURI, LogLevel.VERBOSE);
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

    public Meta createMeta() {
        if (metas_ == null) {
            metas_ = new ArrayList<>();
        }
        Meta meta = new Meta();
        metas_.add(meta);
        return meta;
    }

    // TODO: Make this task able to accept soures other than files

    /**
     * Performs cascading XML document transformation.
     */
    @Override
    public void execute() {
        Map<String, Function<URI, String>> metaMap = createMetaFuncMap();

        // Arrange various directories.
        File projectBaseDir = getProject().getBaseDir();
        if (projectBaseDir == null) {
            projectBaseDir = new File("");
        }
        Path baseDirPathAbsolute = projectBaseDir.getAbsoluteFile().toPath();
        if (baseDir_ == null) {
            baseDir_ = baseDirPathAbsolute;
        } else {
            baseDir_ = baseDirPathAbsolute.resolve(baseDir_);
        }
        if (srcDir_ == null) {
            srcDir_ = baseDir_;
        } else {
            srcDir_ = baseDir_.resolve(srcDir_);
        }

        // Find files to process.
        String[] includedFiles;
        URI[] includedURIs;
        {
            DirectoryScanner scanner = getDirectoryScanner(srcDir_.toFile());
            scanner.scan();
            includedFiles = scanner.getIncludedFiles();
            if (includedFiles.length == 0) {
                sinks_.log(this, "No input sources found", LogLevel.INFO);
                return;
            }
            includedURIs = Arrays.stream(includedFiles)
                                 .map(srcDir_::resolve)
                                 .map(Path::toUri)
                                 .toArray(URI[]::new);
        }
        sinks_.log(this, includedURIs.length + " input sources found", LogLevel.INFO);

        // Set up namespace context.
        NamespaceContext namespaceContext = createNamespaceContext();

        try {
            // Set up XML engines.
            SAXParser parser;
            Transformer identity;
            {
                SAXParserFactory pfac = SAXParserFactory.newInstance();
                pfac.setNamespaceAware(true);
                pfac.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
                parser = pfac.newSAXParser();
                identity = TransformerFactory.newInstance().newTransformer();
            }

            sinks_.init(baseDir_.toFile(), namespaceContext, force_);
            // TODO: pass SAXTransformerFactory

            // Tell whether destinations are older.
            boolean[] includes = null;
            if (!force_) {
                boolean[] examined = sinks_.preexamineBundle(
                    includedURIs, includedFiles, Collections.<URI>emptySet());
                if (IntStream.range(0, examined.length).noneMatch(i -> examined[i])) {
                    sinks_.log(this, "No input sources processed", LogLevel.INFO);
                    sinks_.log(this, "  Skipped input sources are", LogLevel.DEBUG);
                    Arrays.stream(includedURIs)
                        .forEach(u -> sinks_.log(this, "    " + u.toString(), LogLevel.DEBUG));
                    return;
                }
                includes = examined;
            }

            EntityResolver resolver = null;
            if (usesCache_) {
                resolver = new CachingResolver(
                    u -> sinks_.log(this, "Caching " + u, LogLevel.DEBUG),
                    u -> sinks_.log(this, "Reusing " + u, LogLevel.DEBUG));
            }

            int count = 0;
            sinks_.startBundle();
            for (int i = 0; i < includedFiles.length; ++i) {
                URI includedURI = includedURIs[i];
                String systemID = includedURI.toString();
                if ((includes != null) && !includes[i]) {
                    sinks_.log(this, "Skipping " + systemID, LogLevel.DEBUG);
                    continue;
                }
                ++count;
                try {
                    sinks_.log(this, "Processing " + systemID, LogLevel.VERBOSE);
                    List<XPathExpression> referents = sinks_.referents(i, includedURI, includedFiles[i]);
                    List<String> referredContents = Collections.emptyList();
                    Source source;
                    if (!referents.isEmpty()) {
                        sinks_.log(this,
                            "  Referral to the source contents required", LogLevel.DEBUG);
                        DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                        dbfac.setNamespaceAware(true);
                        DocumentBuilder builder = dbfac.newDocumentBuilder();
                        builder.setEntityResolver(resolver);
                        Document document = builder.parse(systemID);
                        referredContents = Referral.extract(document, referents);
                        sinks_.log(this, "  Referred source data: "
                            + String.join(", ", referredContents), LogLevel.DEBUG);

                        if (metaMap != null) {
                            DocumentFragment metas = document.createDocumentFragment();
                            addMetaInformation(metaMap, includedURI, (target, data) ->
                                metas.appendChild(
                                    document.createProcessingInstruction(target, data)));
                            Element docElem = document.getDocumentElement();
                            docElem.insertBefore(metas, docElem.getFirstChild());
                        }

                        source = new DOMSource(document, systemID);

                    } else {
                        XMLReader reader = parser.getXMLReader();
                        sinks_.log(this,
                            "  Referral to the source contents not required", LogLevel.DEBUG);
                        if (metaMap != null) {
                            reader = new MetaFilter(reader,
                                c -> addMetaInformation(metaMap, includedURI, c));
                        }
                        reader.setEntityResolver(resolver);
                        InputSource input = new InputSource(systemID);
                        source = new SAXSource(reader, input);
                    }

                    // Do processing.
                    Result result = sinks_.startOne(i, includedURI, includedFiles[i], referredContents);
                    if (result != null) {
                        identity.transform(source, result);
                        sinks_.finishOne();
                    }

                    parser.reset();
                    identity.reset();

                } catch (TransformerException | IOException e) {
                    sinks_.log(this, "Aborting processing " + systemID, e, LogLevel.WARN);
                    sinks_.abortOne();
                }
            }
            if (count > 0) {
                sinks_.log(this,
                    "Finishing results of " + count +" input sources", LogLevel.VERBOSE);
            } else {
                sinks_.log(this, "No input sources processed", LogLevel.INFO);
            }
            sinks_.finishBundle();
        } catch (SAXException | ParserConfigurationException | TransformerException e) {
            throw new BuildException(e);
        }
    }

    private Map<String, Function<URI, String>> createMetaFuncMap() {
        Map<String, Function<URI, String>> metaMap = null;
        if (metas_ != null) {
            metaMap = new TreeMap<>();
            for (Meta meta : metas_) {
                Map.Entry<String, Function<URI, String>> entry;
                try {
                    entry = meta.yield();
                } catch (BuildException e) {
                    sinks_.log(this, e.getMessage(), LogLevel.ERR);
                    throw e;
                }
                if (metaMap.put(entry.getKey(), entry.getValue()) != null) {
                    sinks_.log(this,
                        "Meta-information name " + entry.getKey() + " added twice", LogLevel.ERR);
                    throw new BuildException();
                }
            }
        }
        return metaMap;
    }

    private NamespaceContext createNamespaceContext() {
        int unmappedNameCount = prefixCount_ - prefixMap_.size();
        if (unmappedNameCount > 0) {
            sinks_.log(this,
                unmappedNameCount + " namespace prefixes left not fully configured", LogLevel.ERR);
            Optional<String> paramNames = prefixMap_.entrySet().stream()
                                            .filter(e -> e.getValue() == null)
                                            .map(e -> e.getKey())
                                            .reduce((r, s) -> r += ", " + s);
            if (paramNames.isPresent()) {
                sinks_.log(this,
                    "  Namespace prefixes without names are: " + paramNames.get(), LogLevel.ERR);
            }
            throw new BuildException();
        }
        return new PrefixMap(prefixMap_);
    }

    private void addMetaInformation(Map<String, Function<URI, String>> metaFuncMap, URI sourceURI, BiConsumer<String, String> consumer) {
        for (Map.Entry<String, Function<URI, String>> metaFunc : metaFuncMap.entrySet()) {
            String target = metaFunc.getKey();
            String data = metaFunc.getValue().apply(sourceURI);
            sinks_.log(this, "Adding processing instruction; target=" + target + ", data=" + data,
                LogLevel.DEBUG);
            consumer.accept(target, data);
        }
    }

    private static final class MetaFilter extends XMLFilterImpl {

        private Consumer<BiConsumer<String, String>> adder_;

        public MetaFilter(XMLReader parent, Consumer<BiConsumer<String, String>> adder) {
            super(parent);
            adder_ = adder;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            super.startElement(uri, localName, qName, atts);
            if (adder_ != null) {
                try {
                    adder_.accept(this::processingInstructionHacked);
                } catch (BuildException e) {
                    if ((e.getCause() != null) && (e.getCause() instanceof SAXException)) {
                        throw (SAXException) e.getCause();
                    }
                }
                adder_ = null;
            }
        }

        private void processingInstructionHacked(String target, String data) {
            try {
                processingInstruction(target, data);
            } catch (SAXException e) {
                throw new BuildException(e);
            }
        }
    }

    private final class ChionographisLogger implements Logger {

        @Override
        public void log(Object issuer, String message, LogLevel level) {
            Chionographis.this.log(format(issuer, message), translateLevel(level));
        }

        @Override
        public void log(Object issuer, String message, Throwable ex, LogLevel level) {
            Chionographis.this.log(format(issuer, message), ex, translateLevel(level));
        }

        private String format(Object issuer, String message) {
            try (Formatter formatter = new Formatter()) {
                String className = issuer.getClass().getSimpleName();
                formatter.format("%13s(%08x): %s",
                    className.substring(0, Math.min(13, className.length())),
                    System.identityHashCode(issuer),
                    message);
                return formatter.toString();
            }
        }

        private int translateLevel(LogLevel level) {
            LogLevel mutated = level;
            if (Chionographis.this.verbose_) {
                if (level == LogLevel.VERBOSE) {
                    mutated = LogLevel.INFO;
                } else if (level == LogLevel.DEBUG) {
                    mutated = LogLevel.VERBOSE;
                }
            }
            return mutated.getLevel();
        }
    }

    private static final class PrefixMap implements NamespaceContext {

        Map<String, String> prefixMap_;

        public PrefixMap(Map<String, String> prefixMap) {
            prefixMap_ = prefixMap;
        }

        @Override
        public String getNamespaceURI(String prefix) {
            return prefixMap_.get(prefix);
        }

        @Override
        public String getPrefix(String namespaceURI) {
            Iterator<String> i = getPrefixes(namespaceURI);
            return i.hasNext() ? i.next() : null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            if (namespaceURI == null) {
                throw new IllegalArgumentException("Namespace URI is null");
            } else if (namespaceURI.equals(XMLConstants.XML_NS_URI)) {
                return Collections.singleton(XMLConstants.XML_NS_PREFIX).iterator();
            } else if (namespaceURI.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI)) {
                return Collections.singleton(XMLConstants.XMLNS_ATTRIBUTE).iterator();
            } else {
                return prefixMap_.entrySet().stream()
                    .filter(e -> e.getKey().equals(namespaceURI))
                    .map(e -> e.getKey())
                    .iterator();
            }
        }

    }
}
