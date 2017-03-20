/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.resources.FileResource;

/**
 * An interface of objects which can tell whether there are any newer resource referenced by the
 * specified file.
 */
interface ReferencedSources {
    /**
     * Searches files referenced by the specified file which are updated after the specified time.
     *
     * @param file
     *      a file from which the search starts.
     *      The last modification time of this file itself is not taken into account.
     * @param lastModified
     *      the time from epoch.
     * @param reentry
     *      the recursive call target, which must not be {@code null}.
     *      Implementation classes need not be afraid of circular reentrance on using this.
     *
     * @return
     *      if it is considered that there is at least one file updated after
     *      <var>lastModified</var> which is referred by <var>file</var>,
     *      a {@link Resource} which possively points it; otherwise {@code null}.
     */
    Resource findAnyNewerSource(File file, long lastModified, ReferencedSources reentry);

    /**
     * A dumb {@link ReferencedSources} object whose {@link #findAnyNewerSource(File, long,
     * ReferencedSources)} always returns {@code null}.
     */
    static final ReferencedSources EMPTY = (f, l, r) -> null;

    /**
     * Combines multiple objects of this type into one.
     *
     * @param finders
     *      a collection of objects of this type, which is shallow-copied by this function.
     *
     * @return
     *      the resulted object, which shall not be {@code null}.
     */
    static ReferencedSources combine(Iterable<ReferencedSources> finders) {
        List<ReferencedSources> fs = StreamSupport.stream(finders.spliterator(), false)
                                                  .collect(Collectors.toList());
        switch (fs.size()) {
        case 0:
            return EMPTY;
        case 1:
            return fs.get(0);
        default:
            return (file, lastModified, reentry) ->
                fs.stream()
                  .map(f -> f.findAnyNewerSource(file, lastModified, reentry))
                  .filter(f -> f != null)
                  .findAny()
                  .orElse(null);
        }
    }

    /**
     * Creates a function which searches files referred by the specified file which are updated
     * after the time specified as its parameter.
     *
     * @param file
     *      a file from which the search starts (hereinafter called <var>f</var>).
     *
     * @return
     *      a function which:
     *      <ul>
     *      <li>takes a time from epoch (hereinafter called <var>l</var>); and</li>
     *      <li>if it is considered that <var>f</var> or one of its referent is newer than
     *      <var>l</var>, returns a {@link Resource} object which possively points it, or</li>
     *      <li>returns {@code null} otherwise.</li>
     *      </ul>
     */
    default LongFunction<Resource> close(File file) {
        long l = file.lastModified();
        return lastModified -> {
            if ((l == 0) || (l > lastModified)) {
                return new FileResource(file);
            } else {
                ReferencedSources bare = this;
                Set<File> s = new HashSet<>();
                ReferencedSources checking =
                    (file2, lastModified2, reentry) -> {
                        File canon;
                        try {
                            canon = file2.getCanonicalFile();
                        } catch (IOException e) {
                            // If canonicalization fails, the source should be considered as
                            // "unknown", or "very-new".
                            return new FileResource(file2);
                        }
                        if (s.contains(canon)) {
                            // Circular dependency is simply ignored
                            return null;
                        }
                        s.add(canon);
                        Resource r = bare.findAnyNewerSource(file2, lastModified2, reentry);
                        s.remove(canon);
                        return r;
                    };
                return checking.findAnyNewerSource(file, lastModified, checking);
            }
        };
    }
}
