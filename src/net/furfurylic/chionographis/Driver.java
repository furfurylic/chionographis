/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

/**
 * A <i>driver</i> holds one or more <i>sinks</i> and passes processed documents to them.
 *
 * <p>Some drivers can be sinks held by other drivers (they are called <i>filters</i>).
 * In this way process-after-process chains can be formed.</p>
 */
public interface Driver {

    /**
     * Sets whether this driver should proceed processing even if the corresponding destination
     * files are up to date. Defaults to {@code false}.
     *
     * @param force
     *      {@code true} if proceeds even if up to date; {@code false} otherwise.
     */
    void setForce(boolean force);

    /**
     * Adds a {@code Transform} filter which consumes the output of this driver object.
     *
     * @return
     *      a {@code Transform} filter object.
     */
    Filter createTransform();

    /**
     * Adds an {@code All} filter which consumes the output of this driver object.
     *
     * @return
     *      an {@code All} filter object.
     */
    All createAll();

    /**
     * Adds a {@code Snip} filter which consumes the output of this driver object.
     *
     * @return
     *      a {@code Snip} filter object.
     */
    Snip createSnip();

    /**
     * Adds an {@code Output} sink which consumes the output of this driver object.
     *
     * @return
     *      an {@code Output} sink object.
     */
    Output createOutput();
}
