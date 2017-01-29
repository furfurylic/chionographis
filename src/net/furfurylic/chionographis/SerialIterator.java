/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.Iterator;
import java.util.NoSuchElementException;

final class SerialIterator<E> implements Iterator<E> {

    private Iterator<? extends E> currentIterator_;

    private Iterator<? extends E> nextIterator_;

    private Iterator<? extends Iterable<? extends E>> i_;

    public SerialIterator(Iterable<? extends Iterable<? extends E>> collections) {
        i_ = collections.iterator();
        currentIterator_ = seekToNext();
    }

    @Override
    public boolean hasNext() {
        if (currentIterator_ == null) {
            return false;
        }
        if (currentIterator_.hasNext()) {
            return true;
        }
        if (nextIterator_ == null) {
            nextIterator_ = seekToNext();
        }
        return nextIterator_ != null;
    }

    @Override
    public E next() {
        while (true) {
            if (currentIterator_ == null) {
                throw new NoSuchElementException();
            }
            if (currentIterator_.hasNext()) {
                return currentIterator_.next();
            }
            if (nextIterator_ == null) {
                currentIterator_ = seekToNext();
            } else {
                currentIterator_ = nextIterator_;
                nextIterator_ = null;
            }
        }
    }

    private Iterator<? extends E> seekToNext() {
        while (i_.hasNext()) {
            Iterable<? extends E> collection = i_.next();
            if (collection != null) {
                final Iterator<? extends E> it = collection.iterator();
                if (it.hasNext()) {
                    return it;
                }
            }
        }
        return null;
    }

    @Override
    public void remove() {
        if (currentIterator_ == null) {
            throw new IllegalStateException();
        }
        currentIterator_.remove();
    }
}
