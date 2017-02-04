/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.Iterator;
import java.util.function.Function;

/**
 * A class of wrapper iterators which transforms objects returned by the wrapped iterators'
 * {@link Iterator#next()} method.
 *
 * @param <F>
 *      the type of the objects iterated by the wrapped iterator.
 * @param <E>
 *      the type of the objects this iterator object iterates.
 */
final class TransformIterator<F, E> implements Iterator<E> {

    private Iterator<F> it_;
    private Function<F, E> transform_;

    public TransformIterator(Iterator<F> it, Function<F, E> transform) {
        it_ = it;
        transform_ = transform;
    }

    @Override
    public boolean hasNext() {
        return it_.hasNext();
    }

    @Override
    public E next() {
        return transform_.apply(it_.next());
    }

    @Override
    public void remove() {
        it_.remove();
    }

}
