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
import java.util.function.LongFunction;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.ProjectComponent;
import org.apache.tools.ant.types.Resource;

/**
 * A <i>sink</i> object is a destination of processed documents.
 * Objects which supplies documents to sinks are called <i>{@linkplain Driver drivers}</i>.
 */
public abstract class Sink extends ProjectComponent {

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
     * @param logger
     *      a logger, which shall not be {@code null}.
     * @param force
     *      whether the driver wants this object to process all inputs regardless
     *      if the output files are up to date or not.
     * @param dryRun
     *      whether the driver wants this object not to finalize its outputs onto external devices.
     */
    abstract void init(File baseDir, NamespaceContext namespaceContext, Logger logger,
        boolean force, boolean dryRun);

    /**
     * Returns XPath expressions which point the source document contents required by this object.
     *
     * <p>When the driver is responsive to the requirement and finds the pointees,
     * the data is informed as the argument of
     * {@link #startOne(int, String, LongFunction, List)}.</p>
     *
     * <p>This method can be invoked after the invocation of {@link #init(File, NamespaceContext,
     * boolean, boolean)} arbitrary number of times.
     * If the driver is not responsive to this sink's request,
     * this method might never be invoked.</p>
     *
     * <p>This method may be called simultaneously by multiple threads on one object.</p>
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
     * <p>This method may be called simultaneously by multiple threads on one object.</p>
     *
     * <p>Callees must not try to modify arrays passed as parameters.</p>
     *
     * @param origSrcFileNames
     *      the source file names of the original input sources to the {@linkplain Chionographis
     *      Chionographis task}, whose elements shall not be {@code null}.
     * @param finders
     *      an array of functions which takes the last modified time of the target from the epoch
     *      and returns non-null reference to a {@link Resource} if the corresponding source file
     *      is considered to be newer than the target (then this reference may point one newer
     *      file), otherwise {@code null}.
     *
     * @return
     *      an array of necessity for a process for each input source;
     *      {@code false} means that this object doesn't require the corresponding input source
     *      to be included in the process,
     *      that is, the corresponding output is already up to date.
     */
    abstract boolean[] preexamineBundle(
        String[] origSrcFileNames, LongFunction<Resource>[] finders);

    abstract void startBundle();

    /**
     * Starts to receive one input document.
     *
     * <p>If this sink object has returned a non-empty list in prior invocation of {@link
     * #referents()}, the driver may pass the referred source contents as
     * {@code referredContents} argument. Note this behavior is optional (that is, the driver can
     * ignore the request made by {@link #referents()}).</p>
     *
     * <p>This method may be called simultaneously by multiple threads on one object.</p>
     *
     * @param origSrcIndex
     *      the index of the corresponding original source,
     *      which meets the index for {@code origSrcFileNames}
     *      parameters in {@link #preexamineBundle(String[], LongFunction[])};
     *      or -1 if the driver have not invoked
     *      {@link #preexamineBundle(String[], LongFunction[])}.
     * @param origSrcFileName
     *      the file name of the corresponding original source,
     *      which is equal to {@code origSrcFileNames[origSrcIndex]} where
     *      {@code origSrcFileNames} is the argument passed in the prior call of
     *      {@link #preexamineBundle(String[], LongFunction[])}; or {@code null} if the driver
     *      have not invoked {@link #preexamineBundle(String[], LongFunction[])}.
     * @param finder
     *      a function which takes the last modified time of the target from the epoch
     *      and returns non-null reference to a {@link Resource} if the corresponding source file
     *      is considered to be newer than the target (then this reference may point one newer
     *      file), otherwise {@code null}.
     * @param referredContents
     *      an list whose size is the same as the return value of
     *      {@link #referents()} and contains the required input document contents
     *      (possibly {@code null}),
     *      or an empty list if the driver is not responsive to the request.
     *
     * @return
     *      an TrAX {@code Result} object which receives the input document; is possibly
     *      {@code null} when this sink judges that there is no need to process the input.
     */
    abstract Result startOne(int origSrcIndex, String origSrcFileName,
        LongFunction<Resource> finder, List<String> referredContents);

    /**
     * Finishes to receive one input document.
     *
     * <p>This method is called after {@link #startOne(int, String, LongFunction, List)} with the
     *  identical TrAX {@code Result} object which it has returned
     *  (only if {@link #abortOne(Result)} has not been invoked).
     *  However, if {@link #startOne(int, String, LongFunction, List)} has returned {@code null},
     *  corresponding call of this method shall not occur.</p>
     *
     * <p>This method may be called simultaneously by multiple threads on one object.
     * It is not guaranteed that the thread which calls this method is identical to the one that
     * called {@link #startOne(int, String, LongFunction, List)}.</p>
     *
     * @param result
     *      an TrAX {@code Result} object identical to what has been returned by
     *      {@link #startOne(int, String, LongFunction, List)} of this object;
     *      which is never {@code null}.
     */
    abstract void finishOne(Result result);

    /**
     * Aborts processing one source document.
     *
     * <p>This method will be called when {@link #startOne(int, String, LongFunction, List)}
     * has been returned successfully and then something that prevents {@link #finishOne(Result)}
     * from being called happened. If {@link #finishOne(Result)} throws an exception, this method
     * will not be invoked.</p>
     *
     * <p>This method shall not throw any exceptions in normal situations.</p>
     *
     * <p>This method may be called simultaneously by multiple threads on one object.
     * It is not guaranteed that the thread which calls this method is identical to the one that
     * called {@link #startOne(int, String, LongFunction, List)}.</p>
     *
     * @param result
     *      an TrAX {@code Result} object identical to what has been returned by
     *      {@link #startOne(int, String, LongFunction, List)} of this object.
     *
     * @return
     *      a reference to a sink for which this aborting shall damage the current bundle and
     *      prevent it from being finished, being {@code null} in ordinary cases.
     */
    abstract Sink abortOne(Result result);

    abstract void finishBundle();
}
