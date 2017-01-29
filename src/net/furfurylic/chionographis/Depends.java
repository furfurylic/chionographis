/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
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

    Depends() {
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
     *
     * @param baseDir
     *
     * @since 1.2
     */
    public void setBaseDir(File baseDir) {
        baseDir_ = baseDir;
    }

    /**
     *
     * @return
     *
     * @since 1.2
     */
    public Depends createDepends() {
        Depends depends = new Depends(logger_, exceptionPoster_);
        children_.add(depends);
        return depends;
    }

    /**
     * Returns the last modified time of the newest resource.
     *
     * @return
     *      -1 if all of the resources are very old (or the caller can neglect its newness),
     *      0 if one of the resource is very new,
     *      positive value otherwise.
     */
    long lastModified() {
        long l = -1;
        for (Resource r : (Iterable<Resource>) (() -> iterator())) {
            long t = lastModifiedOne(r);
            if (t == 0) {
                return 0;
            } else if (t > 0) {
                l = Math.max(l, t);
            } else {
                // ignore minus
            }
        }
        return (l == -1) ? ofAbsent(null) : l;
    }

    private Iterator<Resource> iterator() {
        try {
            return new SerialIterator<>(resources_.getList());
        } catch (BuildException e) {
            // FileSet.iterator() throws an exception when file="a/b/c" and a/b does not exist.
            return Collections.<Resource>emptyIterator();
        }
    }

    private long lastModifiedOne(Resource r) {
        if (r.isExists()) {
            return r.getLastModified();     // positive or 0
        } else {
            return ofAbsent(r);
        }
    }

    private long ofAbsent(Resource r) {
        switch (absent_) {
        case IGNORE:
            return -1;
        case NEW:
            if (r != null) {
                logger_.log(this,
                    "Referred resource \"" + r.toString() + "\" is missing to be regarded as new",
                    Level.INFO);
            } else {
                logger_.log(this,
                    "Referred resources are missing to be regarded as new", Level.INFO);
            }
            return 0;
        case FAIL:
            if (r != null) {
                logger_.log(this,
                    "Required resource \"" + r.toString() + "\" is missing", Level.ERR);
            } else {
                logger_.log(this, "Required resources are missing", Level.ERR);
            }
            throw new BuildException();
        default:
            assert false;
            return 0;
        }
    }


    NewerSourceFinder detach(File baseDir) {
        if (isReference()) {
            dieOnCircularReference();
            Object o = getRefid().getReferencedObject();
            if (o instanceof Depends) {
                return ((Depends) o).detach(baseDir);
            } else {
                throw new BuildException(); // TODO: message?
            }
        }

        // validate all selectors
        validate();

        // set up the base directory
        File myBaseDir = (baseDir_ == null) ? baseDir : baseDir_;

        Predicate<File> selector;
        FileSelector[] selectors = getSelectors(getProject());
        selector = (selectors.length > 0) ?
            (File target) -> {
                return selectors[0].isSelected(
                    myBaseDir, FILE_UTILS.removeLeadingPath(myBaseDir, target), target);
            } :
            (File target) -> true;  // TODO: what if size > 1?

        NewerSourceFinder detachedChild = NewerSourceFinder.combine(
            children_.getList().stream()
                               .map(x -> x.detach(baseDir))
                               .collect(Collectors.toList()));

        Iterable<Resource> sources = () -> new SerialIterator<Resource>(resources_.getList());
        return new DetachedDepends(sources, selector, detachedChild, absent_, logger_);
    }

    private static class DetachedDepends implements NewerSourceFinder {
        private Logger logger_;
        private Absent absent_;

        private Iterable<Resource> sources_;
        private Predicate<File> selector_;
        private NewerSourceFinder child_;

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
            if (!selector_.test(file)) {
                return null;
            }
            if (sourceFiles_ == null) {
                sourceFiles_ = new ArrayList<>();
                newestSource_ = null;
                scanSources: for (Resource source : sources_) {
                    if ((newestSource_ ==  null)
                     || isAbsentOrNewer(source, newestSource_.getLastModified())) {
                        if (newestSource_.getLastModified() == 0){
                            switch (absent_) {
                            case NEW:
                            case FAIL:
                                newestSource_ = source;
                                break scanSources;
                            case IGNORE:
                                continue scanSources;
                            default:
                                assert(false);
                                break;
                            }
                        }
                    }
                    if (source instanceof FileResource) {
                        sourceFiles_.add(((FileResource) source).getFile());
                    }
                }
            }
            if ((newestSource_ != null)
             && isAbsentOrNewer(newestSource_, lastModified)) {
                if (newestSource_.getLastModified() == 0) {
                    switch (absent_) {
                    case IGNORE:
                        break;
                    case NEW:
                        logger_.log(this,
                            "Referred resource \"" + newestSource_ + "\" is missing to be regarded as new",
                            Level.INFO);
                        break;
                    case FAIL:
                        logger_.log(this,
                            "Referred resource \"" + newestSource_ + "\" is missing", Level.ERR);
                        throw new BuildException();
                    default:
                        assert false;
                        break;
                    }
                }
                return newestSource_;
            }
            Optional<Resource> newer =
                sourceFiles_.stream()
                            .map(s -> reentry.findAnyNewerSource(s, lastModified, reentry))
                            .filter(f -> f != null)
                            .findAny();
            if (newer.isPresent()) {
                return newer.get();
            }
            return child_.findAnyNewerSource(file, lastModified, reentry);
        }

        private boolean isAbsentOrNewer(Resource source, long lastModified) {
            long l = source.getLastModified();
            return (l <= 0) || (l > lastModified);
        }
    }
}
