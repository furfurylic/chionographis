/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.lang.ref.SoftReference;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * This class represents a pool of equivalent objects.
 *
 * <p>Objects of this class are thread safe.</p>
 *
 * @param <T>
 *      the type of the pooled objects.
 */
final class Pool<T> {

    public static final Pool<byte[]> BYTES = new Pool<>(() -> new byte[4096]);

    private final ReentrantLock lock_ = new ReentrantLock();
    private Supplier<? extends T> create_;
    private SoftReference<Queue<T>> pool_;

    /**
     * Sole constructor.
     *
     * @param create
     *      a creator of all pooled objects,
     *      which shall not be called simultaneously by multiple threads.
     */
    public Pool(Supplier<? extends T> create) {
        create_ = create;
    }

    /**
     * Takes out a pooled object.
     *
     * @return
     *      an object taken out of this pool.
     */
    public T get() {
        T o = null;
        lock_.lock();
        try {
            if (pool_ != null) {
                Queue<T> queue = pool_.get();
                if (queue != null) {
                    o = queue.poll();
                }
            }
            if (o == null) {
                o = create_.get();
            }
        } finally {
            lock_.unlock();
        }
        return o;
    }

    /**
     * Returns an object to this pool.
     *
     * @param o
     *      an object to be returned to this pool, which has been {@linkplain #get() taken out}
     *      of this pool.
     */
    public void release(T o) {
        lock_.lock();
        try {
            Queue<T> queue = (pool_ != null) ? pool_.get() : null;
            if (queue == null) {
                queue = new ArrayDeque<>();
            }
            queue.offer(o);
            if (pool_ == null) {
                pool_ = new SoftReference<Queue<T>>(queue);
            }
        } finally {
            lock_.unlock();
        }
    }
}
