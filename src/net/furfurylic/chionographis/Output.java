/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.util.FileNameMapper;

/**
 * An <i>Output</i> {@linkplain Sink sink} writes each source document into an filesystem file.
 */
public final class Output extends Sink {
    private static final Pool<ExposingByteArrayOutputStream> BUFFER =
        new Pool<>(() -> new ExposingByteArrayOutputStream());

    private Path destDir_ = null;
    private Path dest_ = null;
    private boolean mkDirs_ = true;
    private String referent_ = null;
    private boolean force_ = false;
    private boolean timid_ = false;
    private FileNameMapper mapper_ = null;

    private Logger logger_;
    private Function<String, Set<Path>> destMapping_ = null;
    private List<XPathExpression> referents_;

    private AtomicInteger countInBundle_;

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     */
    Output(Logger logger) {
        logger_ = logger;
    }

    /**
     * Sets the destination directory. If the given string represents an relative path,
     * it is resolved by {@linkplain Chionographis#setBaseDir(String)
     * the one of the task}.
     *
     * <p>By default, the destination directory is identical to {@linkplain
     * Chionographis#setBaseDir(String) the one of the task}.</p>
     *
     * @param destDir
     *      the destination directory.
     */
    public void setDestDir(String destDir) {
        destDir_ = Paths.get(destDir);
    }

    /**
     * Sets the destination file path. If the given string represents an relative path,
     * it is resolved by {@linkplain #setDestDir(String) the destination directory}.
     *
     * <p>When a destination file path is set, all outputs result to be written
     * into the same single file.
     * If it is not desirable, don't specify the destination file path by this method,
     * instead {@linkplain #add(FileNameMapper) install an file mapper} and/or
     * {@linkplain #setRefer(String) configure to require the content of the source}.</p>
     *
     * @param dest
     *      the destination file path.
     */
    public void setDest(String dest) {
        dest_ = Paths.get(dest);
    }

    /**
     * Specifies an XPath expression which points the source content
     * needed to decide the output file path.
     *
     * <p>If set, the driver of this object searchs the pointed content in the source document.
     * If one is found, the driver reports its string value to this object.</p>
     *
     * <p>The "source documents" in above paragraph are different depending on the drivers.
     * For the {@linkplain Chionographis task} and <i>{@linkplain Snip Snip}</i> drivers,
     * the source documents in which the PI is searched for are the same as their output.
     * On the other hand, for {@linkplain Transform Transform} drivers, they are the literally the
     * source documents (that is, the documents not styled by the XSLT stylesheets yet).
     * And <i>{@linkplain All All}</i> drivers don't do any search because they don't have any
     * particular one source document.</p>
     *
     * <p>This object uses the reported data as if it is set by {@link #setDest(String)}
     * (when no file mapper install) or as if the source file name for the file mapper
     * (when {@linkplain #add(FileNameMapper) they are installed}).</p>
     *
     * <p>If the XPath expression contains names within namespaces, the names shall be accompanied
     * by namespace prefixes as specified in XPath specification.
     * You can define prefix-namespace URI mapping entries in
     * {@linkplain Chionographis#createNamespace() the task}.</p>
     *
     * @param refer
     *      an XPath expression which points the source content
     *      needed to decide the output file path.
     *
     * @see #add(FileNameMapper)
     */
    public void setRefer(String refer) {
        referent_ = refer;
    }

    /**
     * Sets whether this sink should make the destination files' parent directories
     * if necessary. Defaulted to "no".
     *
     * @param mkDirs
     *      {@code true} if makes parent directories; {@code false} otherwise.
     */
    public void setMkDirs(boolean mkDirs) {
        mkDirs_ = mkDirs;
    }

    /**
     * Sets whether this sink should proceed processing even if the destination files are
     * up to date.
     *
     * @param force
     *      {@code true} if proceeds even if up to date; {@code false} otherwise.
     */
    public void setForce(boolean force) {
        force_ = force;
    }

    /**
     * Sets whether this sink should compare existing destination files with the contents
     * about to be written and avoid overwriting them if not necessary.
     *
     * @param timid
     *      {@code true} if avoids unnecessary overwriting; {@code false} otherwise.
     *
     * @since 1.1
     */
    public void setTimid(boolean timid) {
        timid_ = timid;
    }

    /**
     * Installs a file mapper.
     * The file mapper maps a source file name to an destination file name.
     * If the result is not an absolute file path, then it is resolved by {@linkplain
     * #setDestDir(String) the destination directory}.
     *
     * <p>In above paragraph, a "source file name" is the source file name literally
     * (when {@linkplain #setRefer(String) the content of the source is not used}),
     * or the source document content found in the source document (otherwise).</p>
     *
     * @param mapper
     *      a file mapper to be installed.
     *
     * @see #setRefer(String)
     *
     * @throws BuildException
     *      if an mapper has been already installed.
     */
    public void add(FileNameMapper mapper) throws BuildException {
        if (mapper_ != null) {
            logger_.log(this, "File mappers added twice", Logger.Level.ERR);
            throw new BuildException();
        }
        mapper_ = mapper;
    }

    @Override
    void init(File baseDir, NamespaceContext namespaceContext, boolean force) {
        // Configure destDir_ to be an absolute path.
        if (destDir_ == null) {
            destDir_ = baseDir.toPath();
        } else {
            destDir_ = baseDir.toPath().resolve(destDir_);
        }
        assert destDir_.isAbsolute();

        if (dest_ != null) {
            // A predefined destination path exists.
            if (referent_ != null) {
                logger_.log(this,
                    "\"dest\" and \"refer\" can be set exclusively", Logger.Level.ERR);
                throw new BuildException();
            } else if (mapper_ != null) {
                logger_.log(this,
                    "\"dest\" and file mappers can be set exclusively", Logger.Level.ERR);
                throw new BuildException();
            }
            dest_ = destDir_.resolve(dest_);
            assert dest_.isAbsolute();

        } else if (referent_ != null) {
            // No predefined destination path exists
            // and specified to refer the source document contents.
            try {

                XPath xpath = XPathFactory.newInstance().newXPath();
                xpath.setNamespaceContext(namespaceContext);
                referents_ = Collections.singletonList(xpath.compile(referent_));
            } catch (XPathExpressionException e) {
                logger_.log(this,
                    "Failed to compile XPath expression: " + referent_, Logger.Level.ERR);
                throw new BuildException(e);
            }

        } else if (mapper_ == null) {
            // No predefined destination path configured,
            // neither does reference to the source document contents,
            // neither do file mappers.
            // -> No clue to decide the output path.
            logger_.log(this,
                "Neither \"dest\", \"refer\" nor file mappers are set", Logger.Level.ERR);
            throw new BuildException();
        }

        if (referents_ == null) {
            referents_ = Collections.emptyList();
        }

        // Configure destMapping_.
        destMapping_ = null;
        if (mapper_ != null) {
            // There is a mapper and the output path will be decided later using it.
            destMapping_ = s -> {
                if (s != null) {
                    String[] mapped;
                    synchronized (mapper_) {
                        // Ant's FileNameMapper seems not to be thread safe.
                        mapped = mapper_.mapFileName(s);
                    }
                    if (mapped != null) {
                        return Arrays.stream(mapped)
                                     .map(destDir_::resolve)
                                     .collect(Collectors.toSet());
                    }
                }
                return Collections.emptySet();
            };
        } else if (referents_.isEmpty()) {
            // There is no mapper and the output path has been already decided.
            assert dest_ != null;
            assert dest_.isAbsolute();
            destMapping_ = s -> Collections.singleton(dest_);
        }

        force_ = force_ || force;
    }

    @Override
    List<XPathExpression> referents() {
        return referents_;
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, long[] origSrcLastModTimes) {
        boolean[] includes = new boolean[origSrcFileNames.length];
        if (force_ || !referents_.isEmpty()) {
            Arrays.fill(includes, true);
        } else {
            assert destMapping_ != null;
            for (int i = 0; i < origSrcFileNames.length; ++i) {
                includes[i] = isOrigSrcNewer(
                    origSrcLastModTimes[i], destMapping_.apply(origSrcFileNames[i]));
            }
        }
        return includes;
    }

    @Override
    void startBundle() {
        countInBundle_ = new AtomicInteger(0);
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            long origSrcLastModTime, List<String> referredContents) {
        OutputStream buffer = BUFFER.get();

        // Configure dests.
        Set<Path> dests = Collections.<Path>emptySet();
        if (referents_.isEmpty()) {
            assert destMapping_ != null;
            dests = destMapping_.apply(origSrcFileName);
        } else if (!(referredContents.isEmpty() || (referredContents.get(0) == null))) {
            if (destMapping_ != null) {
                dests = destMapping_.apply(referredContents.get(0));
            } else {
                dests = Collections.singleton(destDir_.resolve(referredContents.get(0)));
            }
        }
        if (dests.isEmpty()) {
            logger_.log(this, "Cannot decide the output file path", Logger.Level.ERR);
            throw new BuildException();
        }

        if (!force_ && !isOrigSrcNewer(origSrcLastModTime, dests)) {
            if (dests.size() > 1) {
                String files = dests.stream()
                                    .map(Path::toString)
                                    .collect(Collectors.joining(", "));
                logger_.log(this, "Output files are up to date: " + files, Logger.Level.DEBUG);
            } else {
                logger_.log(this, "The output file is up to date: " + dests.iterator().next(),
                    Logger.Level.DEBUG);
            }
            return null;
        }

        return new OutputStreamResult(buffer, dests);
    }

    private static boolean isOrigSrcNewer(
            long originalSrcLastModifiedTime, Set<Path> dests) {
        if (originalSrcLastModifiedTime <= 0) {
            return true;
        } else {
            return
                dests.stream()
                     .anyMatch(f -> (!Files.exists(f)
                                  || (f.toFile().lastModified() < originalSrcLastModifiedTime)));
        }
    }

    @Override
    void finishOne(Result result) {
        assert result != null;
        assert result instanceof OutputStreamResult : result.getClass();
        OutputStreamResult r = (OutputStreamResult) result;
        ExposingByteArrayOutputStream out = (ExposingByteArrayOutputStream) r.getOutputStream();

        // Write the buffer contents to currentDests_.
        try {
            for (Path mapped : r.getDestinations()) {
                if (mkDirs_) {
                    Path parent = mapped.getParent();
                    if ((parent != null) && !Files.exists(parent)) {
                        Files.createDirectories(parent);
                    }
                }

                Path absolute = mapped.toAbsolutePath();

                if (timid_) {
                    File file = absolute.toFile();
                    if (file.exists() && (file.length() == out.size())
                     && hasIdenticalContent(file, out.buffer())) {
                        logger_.log(this, "No need to overwrite the output file: " + absolute,
                            Logger.Level.FINE);
                        continue;
                    }
                }

                logger_.log(this, "Creating " + absolute, Logger.Level.FINE);
                // We take advantage of FileChannel for its capability to be interrupted
                try {
                    try (FileChannel channel = FileChannel.open(
                            absolute, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                        channel.write(ByteBuffer.wrap(out.buffer(), 0, out.size()));
                    }
                    countInBundle_.incrementAndGet();
                } catch (IOException e) {
                    logger_.log(this, "Failed to create " + absolute, Logger.Level.WARN);
                    logger_.log(this, e, "  Cause: ", Logger.Level.INFO, Logger.Level.VERBOSE);
                     throw new ChionographisBuildException(e);
                }
            }
        } catch (IOException e) {
            throw new BuildException(e);
        } finally {
            placeBackBuffer(out);
        }
    }

    private boolean hasIdenticalContent(File file, byte[] content) throws IOException {
        byte[] bytes = Pool.BYTES.get();
        try (FileChannel in = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int length;
            int head = 0;
            while ((length = in.read(buffer)) > -1) {
                buffer.limit(length);
                buffer.rewind();
                if (!buffer.equals(ByteBuffer.wrap(content, head, length))) {
                    return false;
                }
                head += length;
            }
            return true;
        } finally {
            Pool.BYTES.release(bytes);
        }
    }

    @Override
    void abortOne(Result result) {
        placeBackBuffer(
            (ExposingByteArrayOutputStream) ((OutputStreamResult) result).getOutputStream());
    }

    private void placeBackBuffer(ExposingByteArrayOutputStream buffer) {
        buffer.reset();
        BUFFER.release(buffer);
    }

    @Override
    void finishBundle() {
        switch (countInBundle_.get()) {
        case 0:
            logger_.log(this, "No output files created", Logger.Level.INFO);
            break;
        case 1:
            logger_.log(this, "1 output file created", Logger.Level.INFO);
            break;
        default:
            logger_.log(this, countInBundle_ + " output files created", Logger.Level.INFO);
            break;
        }
    }

    private static class OutputStreamResult extends StreamResult {

        Set<Path> destinations_;

        public OutputStreamResult(OutputStream outputStream, Set<Path> destinations) {
            super(outputStream);
            destinations_ = destinations;
        }

        Set<Path> getDestinations() {
            return destinations_;
        }
    }

    private static class ExposingByteArrayOutputStream extends ByteArrayOutputStream {

        public byte[] buffer() {
            return buf;
        }
    }
}
