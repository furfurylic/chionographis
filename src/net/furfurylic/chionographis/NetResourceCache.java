/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

final class NetResourceCache<T> {

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
    public T get(URI uri,
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
            return cached.get();
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
