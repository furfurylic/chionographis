/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.function.Function;

import javax.xml.namespace.NamespaceContext;

import org.apache.tools.ant.BuildException;

/**
 * An abstract base class for an <i>filter</i>, which is a <i>{@linkplain Driver}</i> and
 * a <i>{@linkplain Sink}</i> at once.
 */
abstract class Filter extends Sink implements Driver {

    private Sinks sinks_;
    private Logger logger_;
    private Function<String, String> propertyExpander_;

    private boolean force_ = false;

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     * @param propertyExpander
     *      an object which expands properties in a text, which shall not be {@code null}.
     */
    Filter(Logger logger, Function<String, String> propertyExpander) {
        sinks_ = new Sinks();
        logger_ = logger;
        propertyExpander_ = propertyExpander;
    }

    /**
     * Returns an composite of {@link Sink}s.
     *
     * @return
     *      an composite of {@link Sink}s, which shall not be {@code null}.
     */
    final Sink sink() {
        return sinks_;
    }

    /**
     * Returns an logger.
     *
     * @return
     *      a logger, which shall not be {@code null}.
     */
    final Logger logger() {
        return logger_;
    }

    /**
     * Returns a property expander, which expands Ant properties in a text.
     *
     * @return
     *      an object which expands properties in a text, which shall not be {@code null}.
     */
    final Function<String, String> propertyExpander() {
        return propertyExpander_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transform createTransform() {
        return sinks_.createTransform(logger_, propertyExpander_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public All createAll() {
        return sinks_.createAll(logger_, propertyExpander_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snip createSnip() {
        return sinks_.createSnip(logger_, propertyExpander_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Output createOutput() {
        return sinks_.createOutput(logger_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setForce(boolean force) {
        force_ = force;
    }

    final boolean isForce() {
        return force_;
    }

    @Override
    final void init(File baseDir, NamespaceContext namespaceContext,
            boolean force, boolean dryRun) {
        if (sinks_.isEmpty()) {
            throw new BuildException("No sinks configured", getLocation());
        }
        force_ = force_ || force;
        doInit(baseDir, namespaceContext, dryRun);
    }

    /**
     * Called by {@link #init(File, NamespaceContext, boolean, boolean)},
     * does the core process of the initialization.
     *
     * @param baseDir
     *      the base directory of the task.
     * @param namespaceContext
     *      an object which maps namespace prefixes into namespace names (URIs).
     * @param dryRun
     *      whether the task executes in the dry run mode.
     */
    abstract void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun);
}
