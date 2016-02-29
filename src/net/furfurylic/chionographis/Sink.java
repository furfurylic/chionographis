/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.Set;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;

/**
 * A <i>sink</i> object is a destination of processed documents.
 * Objects which supplies documents to sinks are called <i>{@linkplain SinkDriver sink drivers}</i>.
 */
public abstract class Sink {
    
    /*
     * This class has no instance fields, but is not an interface due to prevent 
     * its subclasses from exposing methods to public.
     */

    Sink()
    {}

    /**
     * Initializes this object. This method can be invoked once at most in one lifetime.
     * 
     * @param baseDir
     *      the base directory of the {@linkplain Chionographis task},
     *      which represents an absolute file path and is never {@code null}.
     * @param namespaceContext TODO
     */
    abstract void init(File baseDir, NamespaceContext namespaceContext);
    
    /**
     * Receives information about the input sources.
     * 
     * <p>This method should be able to be invoked multiple times in one lifetime,
     * but as of this version, is invoked once at most in one lifetime.</p>
     * 
     * <p>Callees must not try to modify arrays and a set passed as parameters.</p>
     * 
     * @param originalSrcURIs
     *      the URIs of the original input sources to the {@linkplain Chionographis task},
     *      whose elements shall not be {@code null}.
     * @param originalSrcFileNames
     *      the source file names which correspond the URIs in {@code srcURIs},
     *      whose elements shall not be {@code null}.
     * @param additionalURIs
     *      the URIs of the resources which may affects the output contents,
     *      which possibly is an empty set if no such resources are present.
     *      {@code null}s shall not be not contained.
     *
     * @return
     *      an array of necessity for a process for each input source;
     *      {@code false} means that this object doesn't require the corresponding input source
     *      to be included in the process, i.e., the corresponding output is already up-to-date.
     */
    abstract boolean[] preexamineBundle(
        URI[] originalSrcURIs, String[] originalSrcFileNames, Set<URI> additionalURIs);
    
    abstract void startBundle();

    /**
     * Starts to receive one input document.
     * 
     * @param originalSrcIndex
     *      the index of the corresponding input source,
     *      which meets the index for {@code originalSrcURIs} and {@code originalSrcFileNames} 
     *      parameters in {@link #preexamineBundle(URI[], String[], Set)}.
     * @param originalSrcFileName
     *      the file name of the corresponding input source,
     *      which is equal to {@code originalSrcURIs[originalSrcIndex]} for
     *      {@code originalSrcURIs} parameter in {@link #preexamineBundle(URI[], String[], Set)}.
     * 
     * @return
     *      an TrAX {@code Result} object which receives the input document.
     */
    abstract Result startOne(int originalSrcIndex, String originalSrcFileName);

    /**
     * Tells whether this sink requires the source content (which is the data of a processing 
     * instruction (PI) whose target is {@code chionographis-output} and which resides between the 
     * document element and its first child element).
     * 
     * <p>When the driver is responsive to the requirement and finds the PI, the data is informed 
     * as the argument of {@link #finishOne(String)}.</p>
     * 
     * <p>This method can be invoked between the invocation of {@link #startOne(int, String)} and
     * the one of {@link #finishOne(String)}.</p>
     * 
     * <p>The {@code needsOutput} method of {@code Sink} returns {@code false}.</p>
     * 
     * @return
     *      {@code true} if this sink requires the source content; otherwise {@code false}.
     */
    boolean needsOutput() {
        return false;
    }
    
    abstract void finishOne(String output);
    
    abstract void abortOne();

    abstract void finishBundle();
}
