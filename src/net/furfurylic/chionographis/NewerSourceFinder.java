/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.Collection;

import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.resources.FileResource;

@FunctionalInterface
interface NewerSourceFinder {

    default Resource findAnyNewerSourceOrSelfClosed(File file, long lastModified) {
        long l = file.lastModified();
        return ((l == 0) || (l > lastModified)) ?
            new FileResource(file) :
            findAnyNewerSource(file, lastModified, this);
    }

    Resource findAnyNewerSource(File file, long lastModified, NewerSourceFinder reenty);

    static NewerSourceFinder combine(Collection<NewerSourceFinder> os) {
        return (File file, long lastModified, NewerSourceFinder reentry) -> {
            return os.stream()
                     .map(c -> c.findAnyNewerSource(file, lastModified, reentry))
                     .filter(f -> f != null)
                     .findAny()
                     .orElse(null);
        };
    }
}
