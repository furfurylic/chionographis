/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.Collections;
import java.util.List;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.xpath.XPathExpression;

/**
 * A <i>sink</i> object is a destination of processed documents.
 * Objects which supplies documents to sinks are called <i>{@linkplain Driver drivers}</i>.
 */
public abstract class Sink {

    /*
     * This class has no instance fields, but is not an interface due to prevent
     * its subclasses from exposing methods to public.
     */

    /**
     * Sole constructor which does not anything specific.
     */
    Sink()
    {}

    /**
     * Initializes this object. This method can be invoked once at most in one lifetime.
     *
     * @param baseDir
     *      the base directory of the {@linkplain Chionographis task},
     *      which represents an absolute file path and is never {@code null}.
     * @param namespaceContext
     *      the namespace context used to resolve prefixes in the configurations of this object.
     * @param force TODO
     */
    abstract void init(File baseDir, NamespaceContext namespaceContext, boolean force);

    /**
     * Returns XPath expressions which point the source document contents required by this object.
     *
     * <p>When the driver is responsive to the requirement and finds the pointees,
     * the data is informed as the argument of {@link #startOne(int, String, long, List)}.</p>
     *
     * <p>This method can be invoked after the invocation of {@link #init(File, NamespaceContext,
     * boolean)} arbitrary number of times. If the driver is not responsive to this sink's request,
     * this method might never be invoked.</p>
     *
     * <p>The {@code referents} method of {@code Sink} returns an empty list.</p>
     *
     * @return
     *      XPath expressions which point the input document contents required by this object,
     *      or an empty list if this object requires none.
     */
    List<XPathExpression> referents() {
        return Collections.emptyList();
    }

    /**
     * Picks sources to include in the processing from candidate sources.
     *
     * <p>This method should be able to be invoked multiple times in one lifetime,
     * but as of this version, is invoked once at most in one lifetime.
     * If the driver consider all of the candidate are to be included to the processing,
     * this method might never be invoked.</p>
     *
     * <p>Callees must not try to modify arrays passed as parameters.</p>
     *
     * @param originalSrcFileNames
     *      the source file names of the original input sources to the {@linkplain Chionographis
     *      Chionographis task}, whose elements shall not be {@code null}.
     * @param originalSrcLastModifiedTimes
     *      the last modification times of the source files which correspond the file names in
     *      {@code srcURIs} from the epoch, each element of which is positive if significant, or
     *      {@code 0} if unknown.
     *
     * @return
     *      an array of necessity for a process for each input source;
     *      {@code false} means that this object doesn't require the corresponding input source
     *      to be included in the process,
     *      that is, the corresponding output is already up to date.
     */
    abstract boolean[] preexamineBundle(
        String[] originalSrcFileNames, long[] originalSrcLastModifiedTimes);

    abstract void startBundle();

    /**
     * Starts to receive one input document.
     *
     * <p>If this sink object has returned a non-empty list in prior invocation of {@link
     * #referents()}, the driver may pass the referred source contents as
     * <i>referredContents</i> argument. Note this behavior is optional (that is, the driver can
     * ignore the request made by {@link #referents()}).</p>
     *
     * <p>This method, {@link #finishOne(Result)}, and {@link #abortOne(Result)}
     * can be invoked simultaneously by multiple threads.</p>
     *
     * @param originalSrcIndex
     *      the index of the corresponding original source,
     *      which meets the index for {@code originalSrcURIs} and {@code originalSrcFileNames}
     *      parameters in {@link #preexamineBundle(String[], long[])};
     *      or -1 if the driver have not invoked {@link #preexamineBundle(String[], long[])}.
     * @param originalSrcFileName
     *      the file name of the corresponding original source,
     *      which is equal to {@code originalSrcFileNames[originalSrcIndex]} where
     *      {@code originalSrcFileNames} is the argument passed in the prior call of {@link
     *      #preexamineBundle(String[], long[])}; or {@code null} if the driver have not invoked
     *      {@link #preexamineBundle(String[], long[])}.
     * @param originalSrcLastModifiedTime
     *      the last modification time of the original source file from the epoch,
     *      which is positive if significant, or {@code 0} if unknown..
     * @param referredContents
     *      an list whose size is the same as the return value of
     *      {@link #referents()} and contains the required input document contents
     *      (possibly {@code null}),
     *      or an empty list if the driver is not responsive to the request.
     *
     * @return
     *      an TrAX {@code Result} object which receives the input document.
     */
    abstract Result startOne(int originalSrcIndex,
        String originalSrcFileName, long originalSrcLastModifiedTime,
        List<String> referredContents);

    /**
     * Finishes to receive one input document.
     *
     * <p>{@link #startOne(int, String, long, List)}, this method, and {@link #abortOne(Result)}
     * can be invoked simultaneously by multiple threads.</p>
     *
     * @param result
     *      an TrAX {@code Result} object which is returned by {@link #startOne(int, String, long,
     *      List)} of this object.
     */
    abstract void finishOne(Result result);

    /**
     * Aborts processing one source document.
     *
     * <p>Throwing an {@link org.apache.tools.ant.BuildException BuildException} from this method
     * is considered fatal situation (that is, the build process itself will be aborted).</p>
     *
     * <p>{@link #startOne(int, String, long, List)}, {@link #finishOne(Result)}, and this method
     * can be invoked simultaneously by multiple threads.</p>
     *
     * @param result
     *      an TrAX {@code Result} object which is returned by {@link #startOne(int, String, long,
     *      List)} of this object.
     */
    abstract void abortOne(Result result);

    abstract void finishBundle();
}
