/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.xml.namespace.NamespaceContext;

import org.apache.tools.ant.BuildException;

import net.furfurylic.chionographis.Logger.Level;

/**
 * An abstract base class for an <i>filter</i>, which is a <i>{@linkplain Driver}</i> and
 * a <i>{@linkplain Sink}</i> at once.
 */
abstract class Filter extends Sink implements Driver {

    private Sinks sinks_;
    private boolean force_ = false;

    /**
     * Sole constructor.
     *
     * @param logger
     *      a logger, which shall not be {@code null}.
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     * @param exceptionPoster
     *      an object which consumes exceptions occurred during the preparation process;
     *      which shall not be {@code null}.
     */
    Filter(Logger logger, Function<String, String> expander,
                Consumer<BuildException> exceptionPoster) {
        sinks_ = new Sinks(logger, expander, exceptionPoster);
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
        return sinks_;
    }

    /**
     * Returns a property expander, which expands Ant properties in a text.
     *
     * @return
     *      an object which expands properties in a text, which shall not be {@code null}.
     */
    final Function<String, String> expander() {
        return sinks_.expander();
    }

    final Consumer<BuildException> exceptionPoster() {
        return sinks_.exceptionPoster();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transform createTransform() {
        return sinks_.createTransform();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public All createAll() {
        return sinks_.createAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snip createSnip() {
        return sinks_.createSnip();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Output createOutput() {
        return sinks_.createOutput();
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
            sinks_.log(this, "No sinks configured", Level.ERR);
            throw new FatalityException();
        }
        force_ = force_ || force;
        doInit(baseDir, namespaceContext, dryRun);
    }

    abstract void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun);
}
