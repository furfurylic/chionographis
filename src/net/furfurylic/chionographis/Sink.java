/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.xpath.XPathExpression;

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
     * @param namespaceContext
     *      the namespace context used to resolve prefixes in the configurations of this object.
     */
    abstract void init(File baseDir, NamespaceContext namespaceContext);

    /**
     * Picks sources to include in the processing from candidate sources.
     *
     * <p>This method should be able to be invoked multiple times in one lifetime,
     * but as of this version, is invoked once at most in one lifetime.
     * If the driver consider all of the candidate are to be included to the processing,
     * this method might never be invoked.</p>
     *
     * <p>Callees must not try to modify arrays and a set passed as parameters.</p>
     *
     * @param originalSrcURIs
     *      the URIs of the original input sources to the {@linkplain Chionographis task},
     *      whose elements shall not be {@code null}.
     * @param originalSrcFileNames
     *      the source file names which correspond the URIs in {@code srcURIs},
     *      whose elements shall not be {@code null}.
     * @param stylesheetURIs
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
        URI[] originalSrcURIs, String[] originalSrcFileNames, Set<URI> stylesheetURIs);

    abstract void startBundle();

    /**
     * Returns XPath expressions which point the source document contents required by this object.
     *
     * <p>When the driver is responsive to the requirement and finds the pointees,
     * the data is informed as the argument of {@link #startOne(int, URI, String, List)}.</p>
     *
     * <p>This method can be invoked prior to the invocation of {@link #startOne(int, URI, String,
     * List)}. If the driver is not responsive to this sink's request,
     * this method might never be invoked.</p>
     *
     * <p>The {@code referents} method of {@code Sink} returns an empty list.</p>
     *
     * @param originalSrcIndex
     *      the index of the corresponding input source,
     *      which meets the index for {@code originalSrcURIs} and {@code originalSrcFileNames}
     *      parameters in {@link #preexamineBundle(URI[], String[], Set)}.
     * @param originalSrcURI
     *      the URI of the corresponding original source,
     *      which is equal to {@code originalSrcURIs[originalSrcIndex]} where
     *      {@code originalSrcURIs} is the argument passed in the prior call of {@link
     *      #preexamineBundle(URI[], String[], Set)}.
     * @param originalSrcFileName
     *      the file name of the corresponding original source,
     *      which is equal to {@code originalSrcFileNames[originalSrcIndex]} where
     *      {@code originalSrcFileNames} is the argument passed in the prior call of {@link
     *      #preexamineBundle(URI[], String[], Set)}.
     * @return
     *      XPath expressions which point the input document contents required by this object,
     *      or an empty list if this object requires none.
     */
    List<XPathExpression> referents(int originalSrcIndex,
            URI originalSrcURI, String originalSrcFileName) {
        return Collections.emptyList();
    }

    /**
     * Starts to receive one input document.
     *
     * <p>If this sink object has returned a non-empty list in prior invocation of {@link
     * #referents(int, URI, String)}, the driver may pass the referred source contents as
     * <i>referredContents</i> argument. Note this behavior is optional (i.e. the driver can
     * ignore the request made by {@link #referents(int, URI, String)}).</p>
     *
     * @param originalSrcIndex
     *      the index of the corresponding original source,
     *      which meets the index for {@code originalSrcURIs} and {@code originalSrcFileNames}
     *      parameters in {@link #preexamineBundle(URI[], String[], Set)};
     *      or -1 if the driver have not invoked {@link #preexamineBundle(URI[], String[], Set)}.
     * @param originalSrcURI
     *      the URI of the corresponding original source,
     *      which is equal to {@code originalSrcURIs[originalSrcIndex]} where
     *      {@code originalSrcURIs} is the argument passed in the prior call of {@link
     *      #preexamineBundle(URI[], String[], Set)};
     *      or {@code null} if the driver have not invoked
     *      {@link #preexamineBundle(URI[], String[], Set)}.
     * @param originalSrcFileName
     *      the file name of the corresponding original source,
     *      which is equal to {@code originalSrcFileNames[originalSrcIndex]} where
     *      {@code originalSrcFileNames} is the argument passed in the prior call of {@link
     *      #preexamineBundle(URI[], String[], Set)};
     *      or {@code null} if the driver have not invoked
     *      {@link #preexamineBundle(URI[], String[], Set)}.
     * @param referredContents
     *      an list whose size is the same as the return value of
     *      {@link #referents(int, URI, String)}
     *      and contains the required input document contents (possibly {@code null}),
     *      or an empty list if the driver is not responsive to the request.
     *
     * @return
     *      an TrAX {@code Result} object which receives the input document.
     */
    abstract Result startOne(int originalSrcIndex,
        URI originalSrcURI, String originalSrcFileName,
        List<String> referredContents);

    /**
     * Finishes to receive one input document.
     */
    abstract void finishOne();

    /**
     * Aborts processing the current source doucment.
     *
     * <p>Throwing an {@link org.apache.tools.ant.BuildException BuildException} from this method
     * is considered fatal (i.e. the build process itself will be aborted).</p>
     */
    abstract void abortOne();

    abstract void finishBundle();
}
