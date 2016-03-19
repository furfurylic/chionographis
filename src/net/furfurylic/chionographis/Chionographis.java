/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
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
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
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
public final class Chionographis extends MatchingTask implements Driver {

    private Path srcDir_;
    private Path baseDir_;
    private boolean usesCache_ = false;
    private boolean force_ = false;
    private boolean verbose_ = false;

    private List<Namespace> namespaces_ = Collections.emptyList();
    private List<Meta> metas_ = Collections.emptyList();
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void setForce(boolean force) {
        force_ = force;
    }

    /**
     * Sets whether verbose logging should be performed.
     *
     * <p>If set to "yes", log entries whose level is "verbose" or "debug" are escalated to
     * "info" or "verbose" level respectively.</p>
     *
     * @param verbose
     *      {@code true} if verbose logging is performed; {@code false} otherwise.
     */
    public void setVerbose(boolean verbose) {
        verbose_ = verbose;
    }

    /**
     * Add sn instruction to include the meta-information of the original source documents
     * into the processing instruction in the documents emitted to the sinks.
     *
     * <p>The processing instructions appear as the document elements first children.</p>
     *
     * @return
     *      an empty instruction of meta-information processing instruction.
     */
    public Meta createMeta() {
        Meta meta = new Meta(sinks_);
        if (metas_.isEmpty()) {
            metas_ = Collections.singletonList(meta);
        } else {
            if (metas_.size() == 1) {
                Meta first = metas_.get(0);
                metas_ = new ArrayList<>();
                metas_.add(first);
            }
            metas_.add(meta);
        }
        return meta;
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
        Namespace namespace = new Namespace(sinks_);
        if (namespaces_.isEmpty()) {
            namespaces_ = Collections.singletonList(namespace);
        } else {
            if (namespaces_.size() == 1) {
                Namespace first = namespaces_.get(0);
                namespaces_ = new ArrayList<>();
                namespaces_.add(first);
            }
            namespaces_.add(namespace);
        }
        return namespace;
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

    // TODO: Make this task able to accept soures other than files

    /**
     * Performs cascading XML document transformation.
     */
    @Override
    public void execute() {
        // Arrange various directories.
        setUpDirectories();

        // Find files to process.
        String[] includedFiles;
        URI[] includedURIs;
        long[] includedFileLastModifiedTimes;
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
            includedFileLastModifiedTimes = Arrays.stream(includedURIs)
                    .map(File::new)
                    .mapToLong(File::lastModified)
                    .toArray();
        }
        sinks_.log(this, includedURIs.length + " input sources found", LogLevel.INFO);

        // Set up namespace context.
        Map<String, Function<URI, String>> metaFuncMap = createMetaFuncMap();
        NamespaceContext namespaceContext = createNamespaceContext();

        sinks_.init(baseDir_.toFile(), namespaceContext, force_);

        // Tell whether destinations are older.
        boolean[] includes = null;
        if (!force_) {
            boolean[] examined = sinks_.preexamineBundle(
                includedFiles, includedFileLastModifiedTimes);
            if (IntStream.range(0, examined.length).noneMatch(i -> examined[i])) {
                sinks_.log(this, "No input sources processed", LogLevel.INFO);
                sinks_.log(this, "  Skipped input sources are", LogLevel.DEBUG);
                Arrays.stream(includedURIs)
                      .forEach(u -> sinks_.log(this, "    " + u, LogLevel.DEBUG));
                return;
            }
            includes = examined;
        }

        // Set up XML engines.
        EntityResolver resolver = createEntityResolver();
        XMLTransfer xfer = new XMLTransfer(resolver);

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
                List<XPathExpression> referents = sinks_.referents();
                List<String> referredContents;
                Source source;
                if (!referents.isEmpty()) {
                    sinks_.log(this,
                        "  Referral to the source contents required", LogLevel.DEBUG);
                    Document document = xfer.parse(new StreamSource(systemID));
                    referredContents = Referral.extract(document, referents);
                    sinks_.log(this, "  Referred source data: "
                        + String.join(", ", referredContents), LogLevel.DEBUG);

                    if (!metaFuncMap.isEmpty()) {
                        DocumentFragment metas = document.createDocumentFragment();
                        addMetaInformation(metaFuncMap, includedURI, (target, data) ->
                            metas.appendChild(
                                document.createProcessingInstruction(target, data)));
                        Element docElem = document.getDocumentElement();
                        docElem.insertBefore(metas, docElem.getFirstChild());
                    }

                    source = new DOMSource(document, systemID);

                } else {
                    sinks_.log(this,
                        "  Referral to the source contents not required", LogLevel.DEBUG);
                    referredContents = Collections.emptyList();
                    if (!metaFuncMap.isEmpty()) {
                        source = new SAXSource(
                            new MetaFilter(null,
                                c -> addMetaInformation(metaFuncMap, includedURI, c)),
                            new InputSource(systemID));
                    } else {
                        source = new StreamSource(systemID);
                    }
                }

                // Do processing.
                Result result = sinks_.startOne(i, includedFiles[i], includedFileLastModifiedTimes[i], referredContents);
                if (result != null) {
                    xfer.transfer(source, result);
                    sinks_.finishOne();
                }

            } catch (BuildException e) {
                sinks_.log(this, "Aborting processing " + systemID, e, LogLevel.WARN);
                sinks_.abortOne();
                if (e instanceof FatalityException) {
                    throw new BuildException(e.getCause());
                }
            }
        }
        if (count > 0) {
            sinks_.log(this,
                "Finishing results of " + count +" input sources", LogLevel.VERBOSE);
        } else {
            sinks_.log(this, "No input sources processed", LogLevel.INFO);
        }
        sinks_.finishBundle();
    }

    private void setUpDirectories() {
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
    }

    private EntityResolver createEntityResolver() {
        if (usesCache_) {
            return new CachingResolver(
                u -> sinks_.log(this, "Caching " + u, LogLevel.DEBUG),
                u -> sinks_.log(this, "Reusing " + u, LogLevel.DEBUG));
        } else {
            return null;
        }
    }

    private Map<String, Function<URI, String>> createMetaFuncMap() {
        if (metas_.isEmpty()) {
            return Collections.emptyMap();
        }
        if (metas_.size() == 1) {
            Map.Entry<String, Function<URI, String>> entry = metas_.get(0).yield();
            sinks_.log(this,
                "Adding a meta-information instruction: name=" + entry.getKey(),
                LogLevel.VERBOSE);
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        }

        Map<String, Function<URI, String>> metaMap = new TreeMap<>();
        for (Meta meta : metas_) {
            Map.Entry<String, Function<URI, String>> entry;
            try {
                entry = meta.yield();
            } catch (BuildException e) {
                sinks_.log(this, e.getMessage(), LogLevel.ERR);
                throw e;
            }
            sinks_.log(this,
                "Adding a meta-information instruction: name=" + entry.getKey(),
                LogLevel.VERBOSE);
            if (metaMap.put(entry.getKey(), entry.getValue()) != null) {
                sinks_.log(this,
                    "Meta-information instruction named " + entry.getKey() + " added twice",
                    LogLevel.ERR);
                throw new BuildException();
            }
        }
        return metaMap;
    }

    private NamespaceContext createNamespaceContext() {
        if (namespaces_.isEmpty()) {
            return new PrefixMap(Collections.emptyMap());
        }
        if (namespaces_.size() == 1) {
            Map.Entry<String, String> spec = namespaces_.get(0).yield();
            sinks_.log(this, "Adding a namespace prefix mapping: " + spec, LogLevel.VERBOSE);
            return new PrefixMap(
                Collections.singletonMap(spec.getKey(), spec.getValue()));
        }

        Map<String, String> namespaceMap = new HashMap<>();
        for (Namespace namespace : namespaces_) {
            Map.Entry<String, String> spec = namespace.yield();
            sinks_.log(this, "Adding a namespace prefix mapping: " + spec, LogLevel.VERBOSE);
            if (namespaceMap.put(spec.getKey(), spec.getValue()) != null) {
                sinks_.log(this,
                    "Namespace prefix " + spec.getKey() + " added twice",
                    LogLevel.ERR);
                throw new BuildException();
            }
        }
        return new PrefixMap(namespaceMap);
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

        private static final class HackedSAXException extends RuntimeException {

            private static final long serialVersionUID = -6266539332239469415L;

            public HackedSAXException(SAXException cause) {
                super(cause);
            }

            @Override
            public SAXException getCause() {
                return (SAXException) super.getCause();
            }
        }

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
                } catch (HackedSAXException e) {
                    throw e.getCause();
                }
                adder_ = null;
            }
        }

        private void processingInstructionHacked(String target, String data) {
            try {
                processingInstruction(target, data);
            } catch (SAXException e) {
                throw new HackedSAXException(e);
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
