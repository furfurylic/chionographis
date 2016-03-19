/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Consumer;

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

        byte[] cached = BYTES.get(uri, listenStored_, listenHit_, u -> {
            try {
                if (u.getScheme().equalsIgnoreCase("file")) {
                    return Files.readAllBytes(Paths.get(u));
                } else {
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
                }

            } catch (IOException e) {
                return null;
            }
        });

        if (cached != null) {
            InputSource inputSource = new InputSource(new ByteArrayInputStream(cached));
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

        Source cached = TREES.get(uri, listenStored_, listenHit_, u -> {
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
        return cached;
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
}
