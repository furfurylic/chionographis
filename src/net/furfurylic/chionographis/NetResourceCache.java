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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A class for caching objects identified by URIs.
 *
 * The cached objects are held by soft references.
 * Objects of this class are thread-safe.
 *
 * @param <T>
 *      the class of the cached objects.
 */
final class NetResourceCache<T> {

    private final ReentrantLock LOCK = new ReentrantLock();

    /** A synchronized canonicalization mapping for URIs. */
    private Map<URI, WeakReference<URI>> canonURIs_ = null;

    /** A possibly identity-based synchronized map. */
    private SoftReference<Map<URI, Optional<T>>> cache_ = null;

    /** Sole constructor. */
    public NetResourceCache() {
    }

    /**
     * Fetches an object from the cache. If no objects are bound to the specified URI,
     * an object for the URI is created and cached.
     *
     * @param uri
     *      a URI.
     * @param listenStored
     *      a listener invoked when an object is about to be cached.
     * @param listenHit
     *      a listener invoked when an object is fetched from the cache.
     * @param factory
     *      a factory function which make an object from a URI, which can return {@code null}
     *      in case of errors.
     *
     * @return
     *      a possibly-{@code null} resolved object.
     */
    public T get(URI uri,
            Consumer<URI> listenStored, Consumer<URI> listenHit,
            Function<URI, ? extends T> factory) {
        assert uri != null;
        assert uri.isAbsolute();

        Map<URI, Optional<T>> strongOne = null;
        LOCK.lock();
        try {
            if (cache_ == null) {
                canonURIs_ = new WeakHashMap<>();
            } else {
                strongOne = cache_.get();
            }
            if (strongOne == null) {
                strongOne = Collections.synchronizedMap(new IdentityHashMap<>());
                cache_ = new SoftReference<>(strongOne);
            }
        } finally {
            LOCK.unlock();
        }

        // Get the privately-canonicalized form.
        URI canonicalizedURI = canonicalizeURI(uri);

        // From here uri shall NOT be in the canonicalized form.
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
     *      a URI to canonicalize, which must not come from this method.
     *
     * @return
     *      the canonicalized form of the URI,
     *      which is different object from the parameter {@code uri}
     *      only if it has been the first object for its logical content.
     */
    private URI canonicalizeURI(URI uri) {
        assert uri != null;
        synchronized (canonURIs_) {
            WeakReference<URI> ref = canonURIs_.get(uri);
            if (ref == null) {
                ref = new WeakReference<>(uri);
                canonURIs_.put(uri, ref);
                return uri;
            } else {
                assert ref.get() != null;
                return ref.get();
            }
        }
    }
}
