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
    private Logger logger_;
    private Function<String, String> expander_;
    private Consumer<BuildException> exceptionPoster_;

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
        sinks_ = new Sinks();
        logger_ = logger;
        expander_ = expander;
        exceptionPoster_ = exceptionPoster;
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
    final Function<String, String> expander() {
        return expander_;
    }

    final Consumer<BuildException> exceptionPoster() {
        return exceptionPoster_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Transform createTransform() {
        return sinks_.createTransform(logger_, expander_, exceptionPoster_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public All createAll() {
        return sinks_.createAll(logger_, expander_, exceptionPoster_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snip createSnip() {
        return sinks_.createSnip(logger_, expander_, exceptionPoster_);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Output createOutput() {
        return sinks_.createOutput(logger_, exceptionPoster_);
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
            logger_.log(this, "No sinks configured", Level.ERR);
            throw new FatalityException();
        }
        force_ = force_ || force;
        doInit(baseDir, namespaceContext, dryRun);
    }

    abstract void doInit(File baseDir, NamespaceContext namespaceContext, boolean dryRun);
}
