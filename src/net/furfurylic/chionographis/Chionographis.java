/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.PropertyHelper;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.LogLevel;
import org.xml.sax.EntityResolver;

import net.furfurylic.chionographis.Logger.Level;

/**
 * An Ant task class that performs cascading transformation to XML documents.
 * As of now, only files can be original sources of the processing.
 *
 * <p>An object of this class behaves as a <i>driver</i>.</p>
 */
public final class Chionographis extends MatchingTask implements Driver {

    private Path srcDir_;
    private Path baseDir_;
    private boolean usesCache_ = true;
    private boolean force_ = false;
    private boolean verbose_ = false;
    private boolean parallel_ = true;
    private boolean dryRun_ = false;
    private boolean failOnError_ = true;
    private boolean failOnNonfatalError_ = false;
    private Depends depends_ = null;

    private BuildException exsInPreparation_ = null;
    private Assemblage<Namespace> namespaces_ = new Assemblage<>();
    private Assemblage<Meta> metas_ = new Assemblage<>();

    private Sinks sinks_ = null;
    private Logger logger_ = null;

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
        sinks_ = new Sinks();
        logger_ = new ChionographisLogger();
    }

    private Function<String, String> expander() {
        return PropertyHelper.getPropertyHelper(getProject())::replaceProperties;
    }

    private Consumer<BuildException> exceptionPoster() {
        return this::postExceptionInPreparation;
    }

    private void postExceptionInPreparation(BuildException e) {
        if (failOnError_) {
            throw e;
        } else if (exsInPreparation_ == null) {
            exsInPreparation_ = e;
        }
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
     * Defaulted to {@code true}.
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
     * Defaulted to {@code false}.
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
     * Sets whether parallel execution is employed.
     *
     * <p>If set to "yes", execution is done with {@link ForkJoinPool}, which is a statically held
     * thread pool and whose maximum thread count is the available processor count.</p>
     *
     * <p>This attribute is defaulted to {@code true}.</p>
     *
     * @param parallel
     *      {@code true} if parallel execution is employed; {@code false} otherwise.
     */
    public void setParallel(boolean parallel) {
        parallel_ = parallel;
    }

    /**
     * Sets whether "dry run" mode is enabled. The default value is {@code false}.
     *
     * <p>In "dry run" mode, Chionographis does not finalize outputs of the processing.
     * To be specific, it does not write files.</p>
     *
     * <p>You can override this attribute by setting Ant's property
     * {@code net.furfurylic.chionographis.dry-run} to {@code true} or {@code false}.</p>
     *
     * @param dryRun
     *      {@code true} if "dry run" mode is enabled; {@code false} otherwise.
     *
     * @since 1.1
     */
    public void setDryRun(boolean dryRun) {
        dryRun_ = dryRun;
    }

    /**
     * Sets whether the build should fail if a fatal error occurs. Defaults to {@code true}.
     *
     * @param failOnError
     *      {@code true} if the build should fail on fatal errors; {@code false} otherwise.
     *
     * @since 1.1
     */
    public void setFailOnError(boolean failOnError) {
        failOnError_ = failOnError;
    }

    /**
     * Sets whether the build should fail if a nonfatal error occurs. Defaults to {@code false}.
     *
     * <p>A nonfatal error is an error on one input source not likely to affect integrity of
     * processing of other input sources.</p>
     *
     * <p>This attribute has no effect when {@link #setFailOnError(boolean)} is set {@code false}.
     * In fact, setting this attribute to {@code true} escalates nonfatal errors to fatal ones.</p>
     *
     * @param failOnNonfatalError
     *      {@code true} if the build should fail on nonfatal errors; {@code false} otherwise.
     *
     * @since 1.1
     */
    public void setFailOnNonfatalError(boolean failOnNonfatalError) {
        failOnNonfatalError_ = failOnNonfatalError;
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
        Meta meta = new Meta(logger_, exceptionPoster());
        metas_.add(meta);
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
        Namespace namespace = new Namespace(logger_, exceptionPoster());
        namespaces_.add(namespace);
        return namespace;
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
        depends_ = new Depends(logger_, exceptionPoster());
        return depends_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transform createTransform() {
        return sinks_.createTransform(logger_, expander(), exceptionPoster());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public All createAll() {
        return sinks_.createAll(logger_, expander(), exceptionPoster());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snip createSnip() {
        return sinks_.createSnip(logger_, expander(), exceptionPoster());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Output createOutput() {
        return sinks_.createOutput(logger_, exceptionPoster());
    }

    // TODO: Make this task able to accept soures other than files

    /**
     * Performs cascading XML document transformation.
     */
    @Override
    public void execute() {
        failOnNonfatalError_ = failOnError_ && failOnNonfatalError_;
        if (failOnError_) {
            doExecute();
        } else {
            if (exsInPreparation_ != null) {
                logger_.log(this,
                    exsInPreparation_, "Exiting with an error: ", Level.ERR, Level.VERBOSE);
            } else {
                try {
                    doExecute();
                } catch (NonfatalBuildException e) {
                    if (e.isLogged()) {
                        logger_.log(this, "Exiting with an error", Level.ERR);
                    } else {
                        logger_.log(this, e, "Exiting with an error: ", Level.ERR, Level.VERBOSE);
                    }
                } catch (RuntimeException e) {
                    logger_.log(this, e, "Exiting with an error: ", Level.ERR, Level.VERBOSE);
                }
            }
        }
    }

    private void doExecute() {
        // Display version info.
        String implementationVersion = Main.getImplementationVersion();
        if (implementationVersion != null) {
            logger_.log(this, "Starting: v" + implementationVersion, Level.DEBUG);
        } else {
            logger_.log(this, "Starting", Level.DEBUG);
        }

        // Dry run mode?
        boolean dryRun = isDryRun();
        if (dryRun) {
            logger_.log(this, "Executing in DRY RUN mode", Level.INFO);
        }

        if (sinks_.isEmpty()) {
            logger_.log(this, "No sinks configured", Level.ERR);
            throw new BuildException();
        }

        // Arrange various directories.
        setUpDirectories();

        // Find files to process.
        String[] srcFileNames = getIncludedFileNames();
        LogOnce logSrcFound = null;
        switch (srcFileNames.length) {
        case 0:
            logger_.log(this, "No input sources found", Level.INFO);
            return;
        case 1:
            logSrcFound = new LogOnce("1 input source found", Level.INFO);
            break;
        default:
            logSrcFound = new LogOnce(srcFileNames.length + " input sources found", Level.INFO);
            break;
        }

        URI[] srcURIs = Stream.of(srcFileNames)
                              .map(srcDir_::resolve)
                              .map(Path::toUri)
                              .toArray(URI[]::new);
        long[] srcLastModifiedTimes = getLastModifiedTimes(srcURIs);

        sinks_.init(baseDir_.toFile(), createNamespaceContext(), force_, dryRun);

        // Tell whether destinations are older.
        boolean[] includes = (force_ || (srcLastModifiedTimes == null)) ?
            null : sinks_.preexamineBundle(srcFileNames, srcLastModifiedTimes);
        if (includes != null) {
            int includedCount = 0;
            for (int i = 0; i < includes.length; ++i) {
                if (includes[i]) {
                    ++includedCount;
                } else {
                    logSrcFound.run();
                    logger_.log(this, "Skipping " + srcURIs[i], Level.DEBUG);
                }
            }
            if (includedCount == 0) {
                logSrcFound.run();
                logger_.log(this, "No input sources processed", Level.INFO);
                return;
            }
        }

        ChionographisWorkerFactory wfac = new ChionographisWorkerFactory(
            failOnNonfatalError_, srcURIs, srcFileNames, srcLastModifiedTimes,
            sinks_, createEntityResolver(), logger_, createMetaFuncs());

        // This report is placed here in order to appear after all preparation passed in peace.
        logSrcFound.run();

        sinks_.startBundle();

        Stream<IntSupplier> workers = IntStream.range(0, srcFileNames.length)
                                               .filter(i -> (includes == null) || includes[i])
                                               .mapToObj(wfac::create);

        int count;
        ForkJoinPool pool = parallel_ ? ForkJoinPool.commonPool() : null;
        if (pool != null) {
            count = pool.submit(() -> workers.parallel()
                                             .map(wfac::convertToRuiner)
                                             .mapToInt(IntSupplier::getAsInt)
                                             .sum())
                        .join();
        } else {
            count = workers.mapToInt(IntSupplier::getAsInt).sum();
        }

        switch (count) {
        case 0:
            logger_.log(this, "No input sources processed", Level.INFO);
            break;
        case 1:
            logger_.log(this, "Finishing the result of 1 input source", Level.DEBUG);
            break;
        default:
            logger_.log(this,
                "Finishing results of " + count +" input sources", Level.DEBUG);
            break;
        }

        if (pool != null) {
            pool.submit(() -> sinks_.finishBundle()).join();
        } else {
            sinks_.finishBundle();
        }
    }

    private boolean isDryRun() {
        boolean dryRun;
        String dryRunProperty = getProject().getProperty(
            getClass().getPackage().getName() + ".dry-run");
        if (String.valueOf(true).equalsIgnoreCase(dryRunProperty)) {
            dryRun = true;
        } else if (String.valueOf(false).equalsIgnoreCase(dryRunProperty)) {
            dryRun = false;
        } else {
            dryRun = dryRun_;
        }
        return dryRun;
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

    private String[] getIncludedFileNames() {
        DirectoryScanner scanner = getDirectoryScanner(srcDir_.toFile());
        scanner.scan();
        return scanner.getIncludedFiles();
    }

    private long[] getLastModifiedTimes(URI[] uris) {
        LongUnaryOperator mutateLastModified;
        if (depends_ != null) {
            long depends = depends_.lastModified();
            if (depends == 0) {
                // 0 means "unknown"
                long[] zeroes = new long[uris.length];
                Arrays.fill(zeroes, 0);
                return zeroes;
            } else {
                mutateLastModified = l -> Math.max(l, depends);
            }
        } else {
            mutateLastModified = l -> l;
        }
        return Stream.of(uris)
                      .map(File::new)
                      .mapToLong(File::lastModified)
                      .map(mutateLastModified)
                      .toArray();
    }

    private EntityResolver createEntityResolver() {
        if (usesCache_) {
            return new CachingResolver(
                u -> logger_.log(this, "Caching " + u, Level.DEBUG),
                u -> logger_.log(this, "Reusing " + u, Level.DEBUG));
        } else {
            return null;
        }
    }

    private List<Map.Entry<String, Function<URI, String>>> createMetaFuncs() {
        List<Map.Entry<String, Function<URI, String>>> metaFuncs =
            metas_.getList().stream().map(Meta::yield).collect(Collectors.toList());
        metaFuncs.forEach(e -> logger_.log(this,
                    "Adding a meta-information instruction: name=" + e.getKey(), Level.DEBUG));
        return metaFuncs;
    }

    private NamespaceContext createNamespaceContext() {
        Map<String, String> namespaceMap = namespaces_.toMap(Namespace::yield,
            e -> logger_.log(this, "Adding namespace prefix mapping: " + e, Level.DEBUG),
            k -> {
                logger_.log(this, "Namespace prefix " + k + " added twice", Level.ERR);
                throw new BuildException();
            });
        return new PrefixMap(namespaceMap);
    }

    private final class LogOnce implements Runnable {

        private String log_;
        private Level level_;

        public LogOnce(String log, Level level) {
            log_ = log;
            level_ = level;
        }

        @Override
        public void run() {
            if (log_ != null) {
                logger_.log(Chionographis.this, log_, level_);
                log_ = null;
            }
        }
    }

    private static final class ChionographisWorkerFactory {
        private boolean failOnNonfatalError_;
        private URI[] uris_;
        private String[] fileNames_;
        private long[] lastModifiedTimes_;
        private XMLTransfer xfer_;
        private Sink sink_;
        private Logger logger_;
        private List<Map.Entry<String, Function<URI, String>>> metaFuncs_;
        private volatile int isOK_;

        public ChionographisWorkerFactory(
                boolean failOnNonfatalError,
                URI[] uris, String[] fileNames, long[] lastModifiedTimes,
                Sink sink, EntityResolver resolver, Logger logger,
                List<Map.Entry<String, Function<URI, String>>> metaFuncs) {
            failOnNonfatalError_ = failOnNonfatalError;
            uris_ = uris;
            fileNames_ = fileNames;
            lastModifiedTimes_ = lastModifiedTimes;
            sink_ = sink;
            xfer_ = new XMLTransfer(resolver);
            logger_ = logger;
            metaFuncs_ = metaFuncs;
            isOK_ = 1;
        }

        public IntSupplier create(int index) {
            long lastModifiedTime = (lastModifiedTimes_ != null)
                ? lastModifiedTimes_[index] : 0;
            return new ChionographisWorker(failOnNonfatalError_, index,
                uris_[index], fileNames_[index], lastModifiedTime,
                sink_, logger_, metaFuncs_, xfer_,
                () -> isOK_)::run;
        }

        public IntSupplier convertToRuiner(IntSupplier worker) {
            return () -> {
                try {
                    return worker.getAsInt();
                } catch (Error e) {
                    ruin();
                    throw e;
                } catch (RuntimeException e) {
                    ruin();
                    throw e;
                }
            };
        }

        private void ruin() {
            isOK_ = 0;
        }
    }

    private final class ChionographisLogger implements Logger {
        @Override
        public void log(Object issuer, String message, Level level) {
            Chionographis.this.log(head(issuer) + message, translateLevel(level));
        }

        @Override
        public void log(Object issuer, Throwable ex, String heading,
                Level headingLevel, Level bodyLevel) {
            // If failOnNonfatalError_, exceptions reported other than this task is redundant
            // (those exceptions are likely to be reported by Ant after failing).
            if (!failOnNonfatalError_ || (issuer == null) || (issuer == Chionographis.this)) {
                logEx(issuer, ex, heading, headingLevel, bodyLevel);
            }
        }

        private void logEx(Object issuer, Throwable ex, String heading,
                Level headingLevel, Level bodyLevel) {
            String indent;
            String trimmedHead;
            {
                Pattern lineHeadSpace = Pattern.compile("^([\\s]*)([\\S].*)$");
                Matcher matcher = lineHeadSpace.matcher(heading);
                if (matcher.find()) {
                    indent = matcher.group(1);
                    trimmedHead = matcher.group(2);
                } else {
                    indent = "";
                    trimmedHead = "";
                }
            }

            List<String> lines;
            {
                String all = trimmedHead + getPrintedStackTrace(ex);
                all = all.replaceAll("\t", "    ");   // May be controversial

                String[] linesArray = all.split("\\r\\n|\\r|\\n");
                for (int i = 0; i < linesArray.length; ++i) {
                    linesArray[i] = head(issuer) + indent + linesArray[i];
                }
                lines = Arrays.asList(linesArray);
            }

            if (lines.size() > 0) {
                synchronized (this) {
                    Chionographis.this.log(lines.get(0), translateLevel(headingLevel));
                    if (lines.size() > 1) {
                        String delimiter = System.getProperty("line.separator");
                        Chionographis.this.log(
                            String.join(delimiter, lines.subList(1, lines.size())),
                            translateLevel(bodyLevel));
                    }
                }
            }
        }

        private String getPrintedStackTrace(Throwable ex) {
            StringWriter writer = new StringWriter();
            try (PrintWriter out = new PrintWriter(writer)) {
                ex.printStackTrace(out);
            }
            String result = writer.toString();
            // Some exception classes (for example, Ant's BuildException)
            // don't write the class name first, so we supplement it
            if (!result.startsWith(ex.getClass().getName())) {
                result = ex.getClass().getName() + ": " + result;
            }
            // StringWriter.close() has no effect so we don't close
            return result;
        }

        private String head(Object issuer) {
            if (issuer == null) {
                issuer = Chionographis.this;
            }
            try (Formatter formatter = new Formatter()) {
                String className = issuer.getClass().getSimpleName();
                formatter.format("%13s@%08x(%02x): ",
                    className.substring(0, Math.min(13, className.length())),
                    System.identityHashCode(issuer),
                    Thread.currentThread().getId());
                return formatter.toString();
            }
        }

        private int translateLevel(Level level) {
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
            if (prefix == null) {
                throw new IllegalArgumentException("Prefix is null");
            } else if (prefix.equals(XMLConstants.XML_NS_PREFIX)) {
                return XMLConstants.XML_NS_URI;
            } else if (prefix.equals(XMLConstants.XMLNS_ATTRIBUTE)) {
                return XMLConstants.XMLNS_ATTRIBUTE_NS_URI;
            } else {
                String uri = prefixMap_.get(prefix);
                return (uri == null) ? XMLConstants.NULL_NS_URI : uri;
            }
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
