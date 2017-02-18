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

interface NewerSourceFinder {

    /**
     * Searches files referred by the specified file which are updated after the specified time.
     *
     * @param file
     *      a file from which the search starts.
     *      The last modification time of this file itself is not taken into account.
     * @param lastModified
     *      the time from epoch.
     * @param reentry
     *      the recursive call target, which must not be {@code null}.
     *      Implementation classes may not be afraid of circular reentrance on using this.
     *
     * @return
     *      if it is considered that there is at least one updated after <var>lastModified</var>
     *      which is referred by <var>file</var>, a {@link Resource} which possively points it;
     *      otherwise {@code null}.
     */
    Resource findAnyNewerSource(File file, long lastModified, NewerSourceFinder reentry);

    /**
     * A dumb {@code NewerSourceFinder} whose {@link #findAnyNewerSource(File, long,
     * NewerSourceFinder)} always returns {@code null}.
     */
    static final NewerSourceFinder OF_NONE = (f, l, r) -> null;

    /**
     * Combines multiple objects of this type into one.
     *
     * @param finders
     *      a collection of objects of this type, which is shallow-copied by this function.
     *
     * @return
     *      the resulted object, which shall not be {@code null}.
     */
    static NewerSourceFinder combine(Iterable<NewerSourceFinder> finders) {
        List<NewerSourceFinder> fs = StreamSupport.stream(finders.spliterator(), false)
                                                  .collect(Collectors.toList());
        switch (fs.size()) {
        case 0:
            return OF_NONE;
        case 1:
            return fs.get(0);
        default:
            return (File file, long lastModified, NewerSourceFinder reentry) ->
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
     *      a file from which the search starts (hereinafter called as <var>f</var>).
     *
     * @return
     *      a function which:
     *      <ul>
     *      <li>takes a time from epoch (hereinafter called as <var>l</var>); and</li>
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
                NewerSourceFinder bare = this;
                Set<File> s = new HashSet<>();
                NewerSourceFinder checking =
                    (File file2, long lastModified2, NewerSourceFinder reentry) -> {
                        File canon;
                        try {
                            canon = file2.getCanonicalFile();
                        } catch (IOException e) {
                            // If canonicalization fails, the source should be considered as
                            // "unknown", or "very-new".
                            return new FileResource(file);
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
