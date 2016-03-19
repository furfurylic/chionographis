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
     * files are up to date.
     *
     * @param force
     *      {@code true} if proceeds even if up to date; {@code false} otherwise.
     */
    void setForce(boolean force);

    /**
     * Adds an <i>Transform</i> filter which consumes the output of this driver object.
     *
     * @return
     *      a <i>Transform</i> filter object.
     */
    Transform createTransform();

    /**
     * Adds an <i>All</i> filter which consumes the output of this driver object.
     *
     * @return
     *      a <i>all</i> filter object.
     */
    All createAll();

    /**
     * Adds an <i>Snip</i> filter which consumes the output of this driver object.
     *
     * @return
     *      a <i>snip</i> filter object.
     */
    Snip createSnip();

    /**
     * Adds an <i>Output</i> sink which consumes the output of this driver object.
     *
     * @return
     *      a <i>output</i> sink object.
     */
    Output createOutput();
}
