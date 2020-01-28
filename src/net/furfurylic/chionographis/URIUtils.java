package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

final class URIUtils {

    private URIUtils() {
    }

    public static URI getAbsoluteURI(String s, File baseDir) {
        URI uri = null;
        try {
            // First we try as a URI
            uri = new URI(s);
        } catch (URISyntaxException e) {
            // Second we try as a file
        }
        if ((uri == null) || !uri.isAbsolute()) {
            uri = baseDir.toPath().resolve(s).toUri();
        }
        return uri;
    }
}
