/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

/** A common type for tribool-like attributes. */
enum YesNo {

    /** No explicit instruction. */
    DEFAULT,

    /** An explicit instruction enabling caching. */
    YES,

    /** An explicit instruction disabling caching. */
    NO;

    /**
     * Maps {@code true} to {@link #YES} and {@code false} to {@link #NO}.
     *
     * @param yesNo
     *      a {@code boolean} value which represents an explicit instruction on caching.
     *
     * @return
     *      the mapped value of this {@code Enum} from a {@code boolean} value.
     */
    public static YesNo valueOf(boolean yesNo) {
        return yesNo ? YES : NO;
    }
}
