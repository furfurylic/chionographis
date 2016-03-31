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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.LogLevel;
import org.xml.sax.EntityResolver;

/**
 * An Ant task class that performs cascading transformation to XML documents.
 * As of now, only files can be original sources of the processing.
 *
 * <p>An object of this class behaves as a <i>sink driver</i>.</p>
 */
public final class Chionographis extends MatchingTask implements Driver {

    private Path srcDir_;
    private Path baseDir_;
    private boolean usesCache_ = true;
    private boolean force_ = false;
    private boolean verbose_ = false;
    private int maxWorkers_ = 0;

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

    /**
     * Sets whether the external parsed entities in the original source files are cached.
     * Defaulted to "yes".
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
     * Sets whether verbose logging should be performed.
     *
     * <p>If set to "yes", some log entries with "verbose" log level, such as reporting
     * document output, are promoted to "info" level.</p>
     *
     * @param verbose
     *      {@code true} if verbose logging is performed; {@code false} otherwise.
     */
    public void setVerbose(boolean verbose) {
        verbose_ = verbose;
    }

    /**
     * Sets the maximum number of concurrent workers.
     *
     * <p>If not positive, the maximun number of workers is decided as
     * "available processor count + maximum worker count (this attribute)".</p>
     *
     * <p>In any case, the actual number of workers is not greater than the available processor
     * count, and is not less than 1.</p>
     *
     * <p>This attribute is defaulted to 0.</p>
     *
     * @param maxWorkers
     *      the number of concurrent workers.
     */
    public void setMaxWorkers(int maxWorkers) {
        maxWorkers_ = maxWorkers;
    }

    /**
     * Add an instruction to include the meta-information of the original source documents
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
                sinks_.log(this, "No input sources found", Logger.Level.INFO);
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
        sinks_.log(this, includedURIs.length + " input sources found", Logger.Level.INFO);

        // Set up namespace context.
        Map<String, Function<URI, String>> metaFuncMap = createMetaFuncMap();
        NamespaceContext namespaceContext = createNamespaceContext();

        sinks_.init(baseDir_.toFile(), namespaceContext, force_);

        // Tell whether destinations are older.
        boolean[] includes = force_ ?
            null : sinks_.preexamineBundle(includedFiles, includedFileLastModifiedTimes);
        int includedCount;
        if (includes == null) {
            includedCount = includedFiles.length;
        } else {
            includedCount = 0;
            for (int i = 0; i < includes.length; ++i) {
                if (includes[i]) {
                    ++includedCount;
                } else {
                    sinks_.log(this, "Skipping " + includedURIs[i], Logger.Level.DEBUG);
                }
            }
            if (includedCount == 0) {
                sinks_.log(this, "No input sources processed", Logger.Level.INFO);
                return;
            }
        }

        // Decide parallelism.
        int parallelism;
        {
            int processors = Runtime.getRuntime().availableProcessors();
            if (maxWorkers_ <= 0) {
                parallelism = Math.max(processors + maxWorkers_, 1);
            } else {
                parallelism = Math.min(maxWorkers_, processors);
            }
        }

        sinks_.startBundle();

        EntityResolver resolver = createEntityResolver();
        XMLTransfer xfer = new XMLTransfer(resolver);

        BiConsumer<String, Logger.Level> logger = (s, l) -> sinks_.log(this, s, l);

        Stream<ChionographisCallable> callables =
            IntStream.range(0, includedFiles.length)
                     .filter(i -> (includes == null) || includes[i])
                     .mapToObj(i -> new ChionographisCallable(
                                            i, includedURIs[i], includedFiles[i],
                                            includedFileLastModifiedTimes[i],
                                            sinks_, logger, metaFuncMap, xfer,
                                            () -> {
                                                if (ForkJoinTask.getPool() != null) {
                                                    ForkJoinTask.getPool().shutdownNow();
                                                }
                                            }));

        int count;
        ForkJoinPool pool = null;
        if (parallelism > 1) {
            pool = new ForkJoinPool(parallelism);
            List<ForkJoinTask<Integer>> tasks = callables.map(pool::submit)
                                                         .collect(Collectors.toList());
            count = tasks.stream().mapToInt(ForkJoinTask::join).sum();
        } else {
            count = callables.mapToInt(ChionographisCallable::call).sum();
        }

        if (count > 0) {
            sinks_.log(this,
                "Finishing results of " + count +" input sources", Logger.Level.DEBUG);
        } else {
            sinks_.log(this, "No input sources processed", Logger.Level.INFO);
        }

        if (pool != null) {
            pool.submit(() -> sinks_.finishBundle()).join();
        } else {
            sinks_.finishBundle();
        }
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
                u -> sinks_.log(this, "Caching " + u, Logger.Level.DEBUG),
                u -> sinks_.log(this, "Reusing " + u, Logger.Level.DEBUG));
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
                Logger.Level.DEBUG);
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        }

        Map<String, Function<URI, String>> metaMap = new TreeMap<>();
        for (Meta meta : metas_) {
            Map.Entry<String, Function<URI, String>> entry;
            try {
                entry = meta.yield();
            } catch (BuildException e) {
                sinks_.log(this, e.getMessage(), Logger.Level.ERR);
                throw e;
            }
            sinks_.log(this,
                "Adding a meta-information instruction: name=" + entry.getKey(),
                Logger.Level.DEBUG);
            if (metaMap.put(entry.getKey(), entry.getValue()) != null) {
                sinks_.log(this,
                    "Meta-information instruction named " + entry.getKey() + " added twice",
                    Logger.Level.ERR);
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
            sinks_.log(this, "Adding a namespace prefix mapping: " + spec, Logger.Level.DEBUG);
            return new PrefixMap(
                Collections.singletonMap(spec.getKey(), spec.getValue()));
        }

        Map<String, String> namespaceMap = new HashMap<>();
        for (Namespace namespace : namespaces_) {
            Map.Entry<String, String> spec = namespace.yield();
            sinks_.log(this, "Adding a namespace prefix mapping: " + spec, Logger.Level.DEBUG);
            if (namespaceMap.put(spec.getKey(), spec.getValue()) != null) {
                sinks_.log(this,
                    "Namespace prefix " + spec.getKey() + " added twice",
                    Logger.Level.ERR);
                throw new BuildException();
            }
        }
        return new PrefixMap(namespaceMap);
    }

    private final class ChionographisLogger implements Logger {

        @Override
        public void log(Object issuer, String message, Logger.Level level) {
            Chionographis.this.log(format(issuer, message), translateLevel(level));
        }

        @Override
        public void log(Object issuer, String message, Throwable ex, Logger.Level level) {
            Chionographis.this.log(format(issuer, message), ex, translateLevel(level));
        }

        private String format(Object issuer, String message) {
            try (Formatter formatter = new Formatter()) {
                String className = issuer.getClass().getSimpleName();
                formatter.format("%13s@%08x(%02x): %s",
                    className.substring(0, Math.min(13, className.length())),
                    System.identityHashCode(issuer),
                    Thread.currentThread().getId(),
                    message);
                return formatter.toString();
            }
        }

        private int translateLevel(Logger.Level level) {
            LogLevel mutated = null;
            switch (level) {
            case ERR:
                mutated = LogLevel.ERR;
                break;
            case WARN:
                mutated = LogLevel.WARN;
                break;
            case INFO:
                mutated = LogLevel.INFO;
                break;
            case FINE:
                mutated = Chionographis.this.verbose_ ? LogLevel.INFO : LogLevel.VERBOSE;
                break;
            case VERBOSE:
                mutated = LogLevel.VERBOSE;
                break;
            case DEBUG:
                mutated = LogLevel.DEBUG;
                break;
            default:
                assert false : level;
                break;
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
