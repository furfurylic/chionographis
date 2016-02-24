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
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;

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
    private boolean referContent_;
    private FileNameMapper mapper_;

    private Logger logger_;
    private Function<String, Set<File>> destMapping_;

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
     * {@linkplain #setReferContent(boolean) configure to require the content of the source}.</p> 
     *  
     * @param dest
     *      the destination file path.
     */
    public void setDest(String dest) {
        dest_ = Paths.get(dest);
    }

    /**
     * Specifies if the content of the source is needed to decide the output file path.
     *
     * <p>If set {@code true}, the driver of this object searchs an processing instruction (PI)
     * whose target is {@code chionographis-output} in the source document between the document
     * element and the its first child element.
     * If one is found, the driver removes it from the source document and 
     * reports the data of the PI to this object.</p>
     * 
     * <p>The "source documents" in above paragraph are different depending on the drivers.
     * For the {@linkplain Chionographis task} and <i>{@linkplain Snip Snip}</i> drivers, 
     * the source documents in which the PI is searched for are the same as their output.
     * On the other hand, for {@linkplain Transform Transform} drivers, they are the literally the
     * source documents (i.e., the documents not styled by the XSLT stylesheets yet).
     * And <i>{@linkplain All All}</i> drivers don't do any search because they don't have any
     * particular one source document.</p>
     * 
     * <p>This object uses the reported PI data as if it is set by {@link #setDest(String)}
     * (when no file mapper install) or as if the source file name for the file mapper 
     * (when {@linkplain #add(FileNameMapper) they are installed}).</p>
     *
     * <p>By default the value of this is set to {@code true} if both of {@linkplain 
     * #setDest(String) the destination file path} and {@linkplain #add(FileNameMapper)
     * the file mapper} are not supplied, otherwise set to {@code false}.
     *
     * @param referContent
     *      {@code true} if this object requires the content of the source.
     *
     * @see #add(FileNameMapper)
     */
    public void setReferContent(boolean referContent) {
        referContent_ = referContent;
    }

    public void setMkDirs(boolean mkDirs) {
        mkDirs_ = mkDirs;
    }

    /**
     * Installs a file mapper.
     * The file mapper maps a source file name to an destination file name.
     * If the result is not an absolute file path, then it is resolved by {@linkplain 
     * #setDestDir(String) the destination directory}.
     * 
     * <p>In above paragraph, a "source file name" is the source file name literally 
     * (when {@linkplain #setReferContent(boolean) the content of the source is not used}),
     * or the PI data found in the source document (otherwise).</p>
     * 
     * @param mapper
     *      a file mapper to be installed.
     *
     * @see #setReferContent(boolean)
     * 
     * @throws BuildException
     *      if an mapper has been already installed.
     */
    public void add(FileNameMapper mapper) throws BuildException {
        if (mapper_ != null) {
            throw new BuildException(); // TODO: message
        }
        mapper_ = mapper;
    }

    @Override
    void init(File baseDir, NamespaceContext namespaceContext) {
        if (destDir_ == null) {
            destDir_ = baseDir.toPath();
        } else {
            destDir_ = baseDir.toPath().resolve(destDir_);
        }
        if (dest_!= null) {
            dest_ = destDir_.resolve(dest_);
        }
    }

    @Override
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
            Set<URI> additionalURIs) {
        destMapping_ = null;
        if (mapper_ != null) {
            destMapping_ = s -> Arrays.stream(mapper_.mapFileName(s))
                                    .map(destDir_::resolve)
                                    .map(Path::toFile)
                                    .collect(Collectors.toSet());
        } else if (!referContent_) {
            if (dest_ != null) {
                destMapping_ = s -> Collections.singleton(dest_.toFile());
            } else {
                referContent_ = true;
            }
        }

        boolean[] includes = new boolean[originalSrcFileNames.length];

        if (referContent_) {
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

        if (!referContent_) {
            assert(destMapping_ != null);
            currentDests_.addAll(destMapping_.apply(originalSrcFileName));
        }

        return new StreamResult(currentContent_);
    }

    @Override
    boolean needsOutput() {
        return referContent_;
    }

    @Override
    void finishOne(String output) {
        if (needsOutput()) {
            if (output == null) {
                throw new BuildException(); // TODO: message
            } else if (destMapping_ != null) {
                currentDests_.addAll(destMapping_.apply(output));
            } else {
                currentDests_.add(destDir_.resolve(output).toFile());
            }
        }
        try {
            byte[] content = currentContent_.toByteArray();
            for (File mapped : currentDests_) {
                if (mkDirs_) {
                    File parent = mapped.getParentFile();
                    if (!parent.exists()) {
                        mapped.getParentFile().mkdirs();
                    }
                }
                logger_.log(this, "Creating " + mapped.getAbsolutePath(), LogLevel.VERBOSE);
                try (RandomAccessFile channel = new RandomAccessFile(mapped, "rw")) {
                    channel.write(content);
                    channel.setLength(content.length);
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
