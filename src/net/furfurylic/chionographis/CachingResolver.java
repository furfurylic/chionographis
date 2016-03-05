/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * A class that implements SAX {@link EntityResolver} and TrAX {@link URIResolver}
 * backed by a statically maintained cache.
 *
 * <p>The backing cache is thread safe.</p>
 */
final class CachingResolver implements EntityResolver, URIResolver {

    private static final NetResourceCache<byte[]> BYTES = new NetResourceCache<>();
    private static final NetResourceCache<Source> TREES = new NetResourceCache<>();

    private Consumer<URI> listenStored_;
    private Consumer<URI> listenHit_;

    /**
     * Sole constructor.
     *
     * @param listenStored
     *      a listener which receives notifications when documents are stored to the cache.
     * @param listenHit
     *      a listener which receives notifications when documents in the cache are reused.
     */
    public CachingResolver(Consumer<URI> listenStored, Consumer<URI> listenHit) {
        listenStored_ = listenStored;
        listenHit_ = listenHit;
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException {
        if (systemId == null) {
            return null;
        }
        URI uri = URI.create(systemId);
        uri = uniquifyURI(uri);
        if (uri == null) {
            return null;
        }

        Optional<byte[]> cached = BYTES.accessCache(uri, listenStored_, listenHit_, u -> {
            try {
                if (u.getScheme().equalsIgnoreCase("file")) {
                    File file = new File(u);
                    long length = file.length();
                    if (length <= Integer.MAX_VALUE) {
                        byte[] content = new byte[(int) length];
                        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                            in.readFully(content);
                        }
                        return content;
                    }
                }
                byte[] buffer = new byte[4096];
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = u.toURL().openStream()) {
                    int length;
                    while ((length = in.read(buffer)) != -1) {
                        bytes.write(buffer, 0, length);
                    }
                }
                return bytes.toByteArray();
            } catch (IOException e) {
                return null;
            }
        });

        if (cached.isPresent()) {
            InputSource inputSource = new InputSource(new ByteArrayInputStream(cached.get()));
            inputSource.setSystemId(systemId);
            inputSource.setPublicId(publicId);
            return inputSource;
        } else {
            return null;
        }
    }

    @Override
    public Source resolve(String href, String base) throws TransformerException {
        URI uri;
        if (base == null) {
            uri = URI.create(href);
        } else {
            uri = URI.create(base).resolve(href);
        }
        uri = uniquifyURI(uri);
        if (uri == null) {
            return null;
        }

        Optional<Source> cached = TREES.accessCache(uri, listenStored_, listenHit_, u -> {
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            dbfac.setNamespaceAware(true);
            try {
                DocumentBuilder builder = dbfac.newDocumentBuilder();
                builder.setEntityResolver(this);
                Document document = builder.parse(u.toString());
                return new DOMSource(document, u.toString());
            } catch (ParserConfigurationException | SAXException | IOException e) {
                return null;
            }
        });
        return cached.orElse(null);
    }

    /**
     * Normalizes a URI in terms of its logical content.
     *
     * @param uri
     *      a URI to normalize.
     *
     * @return
     *      the normalized URI.
     */
    private static URI uniquifyURI(URI uri) {
        assert uri != null;
        if (!uri.isAbsolute()) {
            return null;
        }
        if (uri.getScheme().equalsIgnoreCase("file")) {
            // Afraid that omission of "xx/../" may break path meanings for symbolic links
            try {
                uri = Paths.get(uri).toRealPath().toUri();
            } catch (IOException e) {
                return null;
            }
        } else {
            uri = uri.normalize();
        }
        return uri;
    }

    private static class NetResourceCache<T> {

        private final Object LOCK = new Object();

        /** A synchronized canonicalization mapping for URIs. */
        private Map<URI, URI> canonURIs_;

        /** A possibly identity-based synchronized map. */
        private Map<URI, Optional<T>> cache_;

        /**
         *
         * @param uri
         *      a URI.
         * @param factory
         *      a factory function which make an object from a URI.
         *
         * @return
         *      a possibly-empty resolved object.
         */
        public Optional<T> accessCache(URI uri,
                Consumer<URI> listenStored, Consumer<URI> listenHit,
                Function<URI, ? extends T> factory) {
            assert uri != null;
            assert uri.isAbsolute();

            synchronized (LOCK) {
                if (cache_ == null) {
                    canonURIs_ = Collections.synchronizedMap(new WeakHashMap<URI, URI>());
                    cache_ = Collections.synchronizedMap(new IdentityHashMap<>());
                }
            }

            // Get the privately-canonicalized form.
            URI canonicalizedURI = canonicalizeURI(uri);

            // From here uri shall not be in the canonicalized form.
            if (canonicalizedURI == uri) {
                uri = URI.create(uri.toString());
            }

            // Lock with privately-canonicalized form
            // in order to minimize granularity of locks.
            synchronized (canonicalizedURI) {
                Optional<T> cached = cache_.get(canonicalizedURI);
                if (cached != null) {
                    if (!cached.isPresent()) {
                        // Means that an error occurred in the previous try.
                        return null;
                    } else {
                        // Cache hit.
                        listenHit.accept(uri);
                    }
                } else {
                    cached = Optional.<T>ofNullable(factory.apply(uri));
                    if (cached.isPresent()) {
                        listenStored.accept(uri);
                    }
                    cache_.put(canonicalizedURI, cached);
                }
                return cached;
            }
        }

        /**
         * Canonicalizes a URI so that URIs which have the same logical content are one same object.
         *
         * @param uri
         *      a URI to canonicalize.
         *
         * @return
         *      the canonicalized form of the URI,
         *      which is different object from the parameter <i>uri</i>
         *      if it did not come from this method.
         */
        private URI canonicalizeURI(URI uri) {
            assert uri != null;
            URI existing = canonURIs_.putIfAbsent(uri, uri);
            if (existing == null) {
                return uri;
            } else {
                return existing;
            }
        }
    }
}
