/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
import org.apache.tools.ant.types.LogLevel;
import org.apache.tools.ant.util.FileNameMapper;

/**
 * An <i>Output</i> {@linkplain Sink sink} writes each source document into an filesystem file.
 */
public final class Output extends Sink {

    private Path destDir_;
    private Path dest_;
    private boolean mkDirs_;
    private String referent_;
    private boolean force_;
    private FileNameMapper mapper_;

    private Logger logger_;
    private Function<String, Set<File>> destMapping_;
    private List<XPathExpression> referents_;

    private Collection<File> currentDests_;
    private ByteArrayOutputStream currentContent_;
    private int countInBundle_;

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
     * source documents (i.e., the documents not styled by the XSLT stylesheets yet).
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

    public void setMkDirs(boolean mkDirs) {
        mkDirs_ = mkDirs;
    }

    public void setForce(boolean force) {
        force_ = force;
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
            logger_.log(this, "File mappers added twice", LogLevel.ERR);
            throw new BuildException();
        }
        mapper_ = mapper;
    }

    @Override
    void init(File baseDir, NamespaceContext namespaceContext) {
        // Configure destDir_ to be an absolute path.
        if (destDir_ == null) {
            destDir_ = baseDir.toPath();
        } else {
            destDir_ = baseDir.toPath().resolve(destDir_);
        }

        if (dest_!= null) {
            // A predefined destination path exists.
            if (referent_ != null) {
                logger_.log(this, "\"dest\" and \"refer\" can be set exclusively", LogLevel.ERR);
                throw new BuildException();
            } else if (mapper_ != null) {
                logger_.log(this, "\"dest\" and file mappers can be set exclusively", LogLevel.ERR);
                throw new BuildException();
            }
            dest_ = destDir_.resolve(dest_);
        } else if (referent_ != null) {
            // No predefined destination path exists
            // and specified to refer the source document contents.
            try {

                XPath xpath = XPathFactory.newInstance().newXPath();
                xpath.setNamespaceContext(namespaceContext);
                referents_ = Collections.singletonList(xpath.compile(referent_));
            } catch (XPathExpressionException e) {
                logger_.log(this, "Failed to compile XPath expression: " + referent_, LogLevel.ERR);
                throw new BuildException(e);
            }
        } else if (mapper_ == null) {
            // No predefined destination path configured,
            // neither does reference to the source document contents,
            // neither do file mappers.
            // -> No clue to decide the output path.
            logger_.log(this, "Neither \"dest\", \"refer\" nor file mappers are set", LogLevel.ERR);
            throw new BuildException();
        }
        if (referents_ == null) {
            referents_ = Collections.emptyList();
        }

        // Configure destMapping_.
        destMapping_ = null;
        if (mapper_ != null) {
            // There is a mapper and the output path will be decided later using it.
            destMapping_ = s -> Arrays.stream(mapper_.mapFileName(s))
                                    .map(destDir_::resolve)
                                    .map(Path::toFile)
                                    .collect(Collectors.toSet());
        } else if (referents_.isEmpty()) {
            // There is no mapper and the output path has been already decided.
            assert dest_ != null;
            destMapping_ = s -> Collections.singleton(dest_.toFile());
        }
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> additionalURIs) {
        boolean[] includes = new boolean[originalSrcFileNames.length];

        if (force_ || !referents_.isEmpty()) {
            Arrays.fill(includes, true);
            return includes;
        }
        assert(destMapping_ != null);

        boolean hasNonFile = additionalURIs.stream().anyMatch(u -> !u.getScheme().equals("file"));

        long additionalLastModified = hasNonFile ? Long.MAX_VALUE :
            additionalURIs.stream()
                .mapToLong(u -> new File(u).lastModified())
                .max()
                .orElse(Long.MIN_VALUE);

        for (int i = 0; i < originalSrcURIs.length; ++i) {
            if (originalSrcURIs[i].getScheme().equals("file")) {
                long srcLastModified = new File(originalSrcURIs[i]).lastModified();
                long lastModified = Math.max(srcLastModified, additionalLastModified);
                includes[i] =
                    destMapping_.apply(originalSrcFileNames[i]).stream()
                        .anyMatch(f -> (hasNonFile
                                     || !f.exists()
                                     || (f.lastModified() < lastModified)));
            } else {
                includes[i] = true;
            }
        }

        return includes;
    }

    @Override
    void startBundle() {
        countInBundle_ = 0;
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName) {
        if (currentDests_ == null) {
            currentDests_ = new ArrayList<>();
            currentContent_ = new ByteArrayOutputStream();
        } else {
            currentDests_.clear();
            currentContent_.reset();
        }

        if (referents_.isEmpty()) {
            assert destMapping_ != null;
            currentDests_.addAll(destMapping_.apply(originalSrcFileName));
        }

        return new StreamResult(currentContent_);
    }

    @Override
    List<XPathExpression> referents() {
        return referents_;
    }

    @Override
    void finishOne(List<String> referredContents) {
        // Make sure currentDests_ fully configured.
        if (!referents_.isEmpty()) {
            if (referredContents.isEmpty() || (referredContents.get(0) == null)) {
                logger_.log(this, "Cannot decide the output file path", LogLevel.ERR);
                throw new BuildException();
            } else if (destMapping_ != null) {
                currentDests_.addAll(destMapping_.apply(referredContents.get(0)));
            } else {
                currentDests_.add(destDir_.resolve(referredContents.get(0)).toFile());
            }
        }

        // Write the buffer contents to currentDests_.
        try {
            for (File mapped : currentDests_) {
                if (mkDirs_) {
                    File parent = mapped.getParentFile();
                    if (!parent.exists()) {
                        parent.mkdirs();
                    }
                }
                logger_.log(this, "Creating " + mapped.getAbsolutePath(), LogLevel.VERBOSE);
                try (FileOutputStream channel = new FileOutputStream(mapped)) {
                    currentContent_.writeTo(channel);
                }
            }
        } catch (IOException e) {
            throw new BuildException(e);
        }
        ++countInBundle_;
    }

    @Override
    void abortOne() {
    }

    @Override
    void finishBundle() {
        logger_.log(this, countInBundle_ + " output files created", LogLevel.INFO);
    }
}
