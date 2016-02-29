/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

/**
 * A <i>sink driver</i> holds one or more <i>sinks</i> and passes processed documents to them.
 * 
 * <p>Some sink drivers can be sinks held by other sink drivers. In this way
 * process-after-process chains can be formed.</p>
 */
public interface SinkDriver {

    /**
     * Adds an <i>Transform</i> sink which consumes the output of this driver object.
     * 
     * @return
     *      a <i>Transform</i> sink object.
     */
    Transform createTransform();

    /**
     * Adds an <i>All</i> sink which consumes the output of this driver object.
     * 
     * @return
     *      a <i>all</i> sink object.
     */
    All createAll();

    /**
     * Adds an <i>Snip</i> sink which consumes the output of this driver object.
     * 
     * @return
     *      a <i>snip</i> sink object.
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
