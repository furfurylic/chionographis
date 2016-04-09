/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.tools.ant.BuildException;

/**
 * A simple collections class for objects, each element of which represents a key-value pair.
 *
 * @param <T>
 *      the type of the objects which are stores to an object of this class.
 */
final class Auxiliaries<T> {

    private List<T> ts_ = Collections.emptyList();

    /**
     * Sole constructor, which creates an empty collection.
     */
    public Auxiliaries() {
    }

    /**
     * Adds an object to this collection.
     *
     * @param t
     *      the object to be stored, which shall not be {@code null}.
     */
    public void add(T t) {
        if (ts_.isEmpty()) {
            ts_ = Collections.singletonList(t);
        } else {
            if (ts_.size() == 1) {
                T first = ts_.get(0);
                ts_ = new ArrayList<>();
                ts_.add(first);
            }
            ts_.add(t);
        }
    }

    /**
     * Maps each element of this collection to a key-value pair to yield a map.
     *
     * <p>This method does not change the state of this object;
     * that is, this method can be invoked mutiple times.</p>
     *
     * @param <K>
     *      the type of the keys of the map.
     * @param <V>
     *      the type of the values of the map.
     *
     * @param yield
     *      a function which make a key-value pair from an element of this collection.
     * @param logAdded
     *      a logger called just after an key-value pair is stored to the resulting map.
     * @param logTwice
     *      a logger called when duplicated keys are found.
     *      This method will fail just after this logger is called.
     *
     * @return
     *      the resulting map, which is not {@code null}.
     *
     * @throws BuildException
     *      if duplicated keys are detected.
     */
    public <K, V> Map<K, V> toMap(Function<? super T, Map.Entry<K, V>> yield,
            Consumer<Map.Entry<K, V>> logAdded, Consumer<K> logTwice) {
        if (ts_.isEmpty()) {
            return Collections.emptyMap();
        }

        if (ts_.size() == 1) {
            Map.Entry<K, V> entry = yield.apply(ts_.get(0));
            Map<K, V> map = Collections.singletonMap(entry.getKey(), entry.getValue());
            logAdded.accept(entry);
            return map;
        }

        Map<K, V> map = new TreeMap<>();
        for (T t : ts_) {
            Map.Entry<K, V> entry = yield.apply(t);
            if (map.put(entry.getKey(), entry.getValue()) != null) {
                logTwice.accept(entry.getKey());
                throw new BuildException();
            }
            logAdded.accept(entry);
        }
        return map;
    }
}
