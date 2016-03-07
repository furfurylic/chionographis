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
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
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

    private static final ThreadLocal<SoftReference<byte[]>> BUFFER = new ThreadLocal<>();

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

        Optional<byte[]> cached = BYTES.get(uri, listenStored_, listenHit_, u -> {
            try {
                if (u.getScheme().equalsIgnoreCase("file")) {
                    // Files are easy to get lengths in advance.
                    File file = new File(u);
                    long length = file.length();
                    if (length <= Integer.MAX_VALUE) {
                        byte[] content = new byte[(int) length];
                        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                            in.readFully(content);
                        }
                        return content;
                    } else {
                        // Fall through
                    }
                }

                // In case of non-files and too-long files
                byte[] buffer = null;
                {
                    SoftReference<byte[]> ref = BUFFER.get();
                    if (ref != null) {
                        buffer = ref.get();
                    }
                    if (buffer == null) {
                        buffer = new byte[4096];
                        BUFFER.set(new SoftReference<byte[]>(buffer));
                    }
                }
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

        Optional<Source> cached = TREES.get(uri, listenStored_, listenHit_, u -> {
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            dbfac.setNamespaceAware(true);
            try {
                DocumentBuilder builder = dbfac.newDocumentBuilder();
                builder.setEntityResolver(this);    // This hinders making factory static
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
        private Map<URI, WeakReference<URI>> canonURIs_ = null;

        /** A possibly identity-based synchronized map. */
        private SoftReference<Map<URI, Optional<T>>> cache_ = null;

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
        public Optional<T> get(URI uri,
                Consumer<URI> listenStored, Consumer<URI> listenHit,
                Function<URI, ? extends T> factory) {
            assert uri != null;
            assert uri.isAbsolute();

            Map<URI, Optional<T>> strongOne = null;
            synchronized (LOCK) {
                if (cache_ == null) {
                    canonURIs_ = Collections.synchronizedMap(new WeakHashMap<>());
                } else {
                    strongOne = cache_.get();
                }
                if (strongOne == null) {
                    strongOne = Collections.synchronizedMap(new IdentityHashMap<>());
                    cache_ = new SoftReference<>(strongOne);
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
                Optional<T> cached = strongOne.get(canonicalizedURI);
                if (cached != null) {
                    if (!cached.isPresent()) {
                        // Means that an error occurred in the previous try.
                        return null;
                    } else {
                        // Cache hit.
                        listenHit.accept(uri);
                    }
                } else {
                    cached = Optional.ofNullable(factory.apply(uri));
                    if (cached.isPresent()) {
                        listenStored.accept(uri);
                    }
                    strongOne.put(canonicalizedURI, cached);
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
            WeakReference<URI> ref = canonURIs_.putIfAbsent(uri, new WeakReference<>(uri));
            URI existing = (ref != null) ? ref.get() : null;
            if (existing == null) {
                return uri;
            } else {
                return existing;
            }
        }
    }
}
