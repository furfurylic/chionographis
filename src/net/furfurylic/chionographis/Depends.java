/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.types.resources.FileResource;
import org.apache.tools.ant.types.selectors.AbstractSelectorContainer;
import org.apache.tools.ant.types.selectors.FileSelector;
import org.apache.tools.ant.util.FileUtils;

import net.furfurylic.chionographis.Logger.Level;

/**
 * Objects of this class are used to instruct their enclosing <i>{@linkplain Chionographis}</i>
 * drivers or <i>{@linkplain Transform}</i> filters that they shall refer the last modified times
 * of the resources the objects of this class point.
 *
 * <p>For examble, if a <i>{@linkplain Transform}</i> filter is configured to use stylesheet
 * {@code style.xsl} and has an object of this class as its child element which points
 * {@code style-included.xsl}, then the filter shall refer both last modified times
 * and recognizes the latest one as its stylesheet's last modified time.</p>
 */
public final class Depends extends AbstractSelectorContainer {

    private static final FileUtils FILE_UTILS = FileUtils.getFileUtils();

    private Logger logger_;
    private Consumer<BuildException> exceptionPoster_;

    /** Instructions for the case of the pointed resources do not exist. */
    public enum Absent {
        /** Instructs that the task shall be fail. */
        FAIL,

        /**
         * Instructs that the enclosing driver shall behave as if
         * the pointed resource is <em>very</em> new.
         */
        NEW,

        /** Instructs that the enclosing driver shall not be affected by the absence at all. */
        IGNORE
    }

    private Absent absent_ = Absent.FAIL;
    private Assemblage<ResourceCollection> resources_ = new Assemblage<>();
    private File baseDir_;
    private Assemblage<Depends> children_ = new Assemblage<>();

    /**
     * @since 1.2
     */
    public Depends() {
    }

    Depends(Logger logger, Consumer<BuildException> exceptionPoster) {
        logger_ = logger;
        exceptionPoster_ = exceptionPoster;
    }

    /**
     * Sets the instruction for the case that the pointed resources do not exist.
     *
     * @param absent
     *      an instruction which shall be one of {@code fail}, {@code new} and {@code ignore}.
     *      The default value is {@code fail}.
     *
     * @see Absent
     */
    public void setAbsent(String absent) {
        try {
            absent_ = Absent.valueOf(absent.toUpperCase());
        } catch (IllegalArgumentException e) {
            String message = "Bad \"absent\" attribute value: " + absent;
            if (logger_ != null) {
                logger_.log(this, message, Level.ERR);
                exceptionPoster_.accept(new BuildException());
            } else {
                throw new BuildException(message);
            }
        }
    }

    /**
     * Adds the pointed resources.
     *
     * @param resources
     *      the pointed resorces.
     */
    public void add(ResourceCollection resources) {
        resources_.add(resources);
    }

    /**
     * TODO: description
     *
     * @param baseDir
     *      The base directory used by file selectors.
     *
     * @since 1.2
     */
    public void setBaseDir(File baseDir) {
        baseDir_ = baseDir;
    }

    /**
     * TODO: description
     *
     * @return
     *      an empty additional depended resource container object.
     *
     * @since 1.2
     */
    public Depends createDepends() {
        Depends depends = new Depends(logger_, exceptionPoster_);
        children_.add(depends);
        return depends;
    }

    NewerSourceFinder detach() {
        assert logger_ != null;
        return detach(logger_);
    }

    private NewerSourceFinder detach(Logger logger) {
        if (isReference()) {
            // TODO: do more thorough circular reference check
            dieOnCircularReference();
            Object o = getRefid().getReferencedObject();
            if (o instanceof Depends) {
                return ((Depends) o).detach(logger);
            } else {
                throw new BuildException(); // TODO: message?
            }
        }

        Predicate<File> selector = createFileSelector();

        NewerSourceFinder detachedChild = NewerSourceFinder.combine(
            children_.getList().stream()
                               .map(x -> x.detach(logger))
                               .collect(Collectors.toList()));

        Iterable<Resource> sources = resources_.getList().isEmpty() ?
            null :
            () -> new SerialIterator<>(createResourceIterableIterable());

        return new DetachedDepends(sources, selector, detachedChild, absent_, logger);
    }

    private Predicate<File> createFileSelector() {
        switch (selectorCount()) {
        case 0:
            return null;
        case 1:
            break;
        default:
            logger_.log(this, "At most one file selector can be specified", Level.ERR);
            throw new BuildException();
        }

        if (baseDir_ == null) {
            logger_.log(this, "No base directory specified", Level.ERR);
            throw new BuildException();
        }

        validate();

        // In Ant 1.8, selectorElements() returns non-generic Enumeration.
        FileSelector selector = (FileSelector) selectorElements().nextElement();
        return target -> selector.isSelected(
                            baseDir_, FILE_UTILS.removeLeadingPath(baseDir_, target), target);
    }

    @SuppressWarnings("unchecked")
    private Iterable<Iterable<Resource>> createResourceIterableIterable() {
        // In Ant 1.8, ResourceCollection is not a subinterface of Iterable<Collection>
        if (Iterable.class.isAssignableFrom(ResourceCollection.class)) {
            return (List<Iterable<Resource>>) (Object) resources_.getList();
        } else {
            return () -> new TransformIterator<>(
                resources_.getList().iterator(), r -> () -> r.iterator());
        }
    }

    private static class DetachedDepends implements NewerSourceFinder {
        private final ReentrantLock scanLock_ = new ReentrantLock();

        private Logger logger_;
        private Absent absent_;

        private Iterable<Resource> sources_;
        private Predicate<File> selector_;
        private NewerSourceFinder child_;

        private boolean absentSignificantly_ = false;
        private List<File> sourceFiles_ = null;
        private Resource newestSource_ = null;

        public DetachedDepends(Iterable<Resource> sources, Predicate<File> selector,
                NewerSourceFinder child, Absent absent, Logger logger) {
            absent_ = absent;
            logger_ = logger;
            sources_ = sources;
            selector_ = selector;
            child_ = child;
        }

        @Override
        public Resource findAnyNewerSource(
                File file, long lastModified, NewerSourceFinder reentry) {
            if ((selector_ == null) || selector_.test(file)) {
                Resource r = findAnyNewerSourceHere(lastModified, reentry);
                if (r != null) {
                    return r;
                }
            }
            return child_.findAnyNewerSource(file, lastModified, reentry);
        }

        private Resource findAnyNewerSourceHere(long lastModified, NewerSourceFinder reentry) {
            try {
                scanLock_.lock();
                if (sourceFiles_ == null) {
                    scanSources();
                }
            } finally {
                scanLock_.unlock();
            }
            if (absentSignificantly_) {
                return handleSignificantAbsence();
            }
            if ((newestSource_ !=  null) && (newestSource_.getLastModified() > lastModified)) {
                return newestSource_;
            }
            if (selector_ != null) {
                Optional<Resource> newer =
                    sourceFiles_.stream()
                                .map(s -> reentry.findAnyNewerSource(s, lastModified, reentry))
                                .filter(f -> f != null)
                                .findAny();
                if (newer.isPresent()) {
                    return newer.get();
                }
            }
            return null;
        }

        /**
         * Sets up {@link #newestSource_}, {@link #sourceFiles_} and {@link #absentSigificantly_}.
         */
        private void scanSources() {
            newestSource_ = null;
            sourceFiles_ = new ArrayList<>();
            absentSignificantly_ = false;
            if (sources_ == null) {
                return;
            }
            boolean hasAtLeastOne = false;
            for (Resource source : sources_) {
                hasAtLeastOne = true;
                if (source.getLastModified() == 0) {
                    switch (absent_) {
                    case NEW:
                    case FAIL:
                        absentSignificantly_ = true;
                        return;
                    case IGNORE:
                        continue;
                    default:
                        assert false;
                        break;
                    }
                }
                if ((newestSource_ ==  null)
                 || (source.getLastModified() > newestSource_.getLastModified())) {
                    newestSource_ = source;
                }
                if (source instanceof FileResource) {
                    sourceFiles_.add(((FileResource) source).getFile());
                }
            }
            if ((!hasAtLeastOne) && (absent_ != Absent.IGNORE)) {
                absentSignificantly_ = true;
            }
        }

        private Resource handleSignificantAbsence() {
            // FIXME: is newestSource_ referrable?
            switch (absent_) {
            case NEW:
                logger_.log(this,
                    "Referred resource \"" + newestSource_ + "\" is missing to be regarded as new",
                    Level.INFO);
                return new FileResource();
            case FAIL:
                logger_.log(this,
                    "Referred resource \"" + newestSource_ + "\" is missing", Level.ERR);
                throw new BuildException();
            case IGNORE:
            default:
                assert false;
                return null;
            }
        }
    }
}
