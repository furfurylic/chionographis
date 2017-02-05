/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.FileList;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.types.resources.FileResource;
import org.apache.tools.ant.types.selectors.AbstractSelectorContainer;
import org.apache.tools.ant.types.selectors.FileSelector;
import org.apache.tools.ant.types.selectors.FilenameSelector;
import org.apache.tools.ant.util.FileUtils;

import net.furfurylic.chionographis.Logger.Level;

/**
 * This class represents a dependency between resources.
 * Objects of this class are used to instruct their enclosing <i>{@linkplain Chionographis}</i>
 * drivers or <i>{@linkplain Transform}</i> filters that they shall refer the last modified times
 * of the resources pointed by them.
 *
 * <p>For examble, if a <i>{@linkplain Transform}</i> filter is configured to use stylesheet
 * {@code style.xsl} and has an object of this class as its child element which points
 * {@code style-included.xsl}, then the filter shall refer both last modified times
 * and recognizes the latest one as its stylesheet's last modified time.</p>
 *
 * <p>An object of this class can have at most one file selector. If it has one, dependency on
 * resources it points are applied limitedly to the referrers match the file selector.
 * In addition, when it has a file selector, it applies its dependency recursively.</p>
 *
 * <p>Objects of this class can have nested object of this class. This is how you can bundle
 * multiple dependencies into one object.</p>
 */
public final class Depends extends AbstractSelectorContainer {

    private static final FileUtils FILE_UTILS = FileUtils.getFileUtils();

    private Logger logger_;

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
     * Makes an empty dependency object.
     *
     * @since 1.2
     */
    public Depends() {
    }

    Depends(Logger logger) {
        logger_ = logger;
    }

    /**
     * Sets the instruction for the case that the pointed resources do not exist.
     *
     * <p>NOTE: all attributes of this class including this have no effects on nested object of
     * this class.</p>
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
            throw new BuildException("Bad \"absent\" attribute value: " + absent);
        }
    }

    /**
     * Sets the base directory used by the file selector.
     * If this object has a file selector, specifying the base directory is required.
     *
     * <p>NOTE: all attributes of this class including this have no effects on nested object of
     * this class.</p>
     *
     * @param baseDir
     *      The base directory used by the file selectors.
     *
     * @since 1.2
     */
    public void setBaseDir(File baseDir) {
        if (baseDir_ != null) {
            throw new BuildException(
                "\"baseDir\" and \"file\" must be specified exclusively", getLocation());
        }
        baseDir_ = baseDir;
    }

    /**
     * Sets the name of files to which the dependency applies.
     *
     * <p>This method is an abridged version of {@link #addSelector(
     * org.apache.tools.ant.types.selectors.SelectSelector)} with a new {@link FilenameSelector}
     * whose {@linkplain FilenameSelector#setName(String) name} is set to <var>fileName</var>.</p>
     *
     * <p>NOTE: all attributes of this class including this have no effects on nested object of
     * this class.</p>
     *
     * @param fileName
     *      the name of files to which the dependency applies.
     *
     * @since 1.2
     */
    public void setFileName(String fileName) {
        FilenameSelector selector = new FilenameSelector();
        selector.setName(fileName);
        addFilename(selector);
    }

    /**
     * Sets the file to which the dependency applies.
     *
     * <p>This method is an abridged version of the sequence:</p>
     * <ul>
     * <li>calling {@link #setBaseDir(File)} with <var>file</var>{@code .getParentFile()},
     * and then</li>
     * <li>calling {@link #addSelector(org.apache.tools.ant.types.selectors.SelectSelector)} with
     * a new {@link FilenameSelector} whose {@linkplain FilenameSelector#setName(String) name} is
     * set to <var>file</var>{@code .getName()}.</li>
     * </ul>
     *
     * <p>Note that this method and {@link #setBaseDir(File)} can only be called exclusively.</p>
     *
     * <p>NOTE: all attributes of this class including this have no effects on nested object of
     * this class.</p>
     *
     * @param file
     *      the file to which the dependency applies.
     *
     * @since 1.2
     */
    public void setFile(File file) {
        setBaseDir(file.getParentFile());
        setFileName(file.getName());
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
     * Creates a nested object of this class.
     *
     * @return
     *      an empty object which instructs dependency between resources to the enclosing driver.
     *
     * @since 1.2
     */
    public Depends createDepends() {
        Depends depends = new Depends(logger_);
        children_.add(depends);
        return depends;
    }

    /**
     * Creates a new {@link NewerSourceFinder} object configured properly by this object.
     *
     * @return
     *      a new {@link NewerSourceFinder} object.
     */
    NewerSourceFinder detach() {
        dieOnCircularReference();
        assert logger_ != null;
        return detach(logger_);
    }

    @Override
    protected void dieOnCircularReference() {
        dieOnCircularReference(Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private void dieOnCircularReference(Set<Depends> s) {
        if (s.contains(this)) {
            throw circularReference();
        }
        s.add(this);
        if (isReference()) {
            Reference refid = getRefid();
            Object o = refid.getReferencedObject();
            if (o instanceof Depends) {
                ((Depends) o).dieOnCircularReference(s);
            } else {
                logger_.log(this, "Refid \"" + refid.getRefId() + "\" must have type "
                        + Depends.class.getName(), Level.ERR);
                throw new BuildException();
            }
        } else {
            children_.getList().stream().forEach(c -> c.dieOnCircularReference(s));
        }
        s.remove(this);
    }

    private NewerSourceFinder detach(Logger logger) {
        if (isReference()) {
            Object o = getRefid().getReferencedObject();
            assert o instanceof Depends;   // checked in dieOnCircularReference
            return ((Depends) o).detach(logger);
        }

        Predicate<File> selector = createFileSelector();

        NewerSourceFinder detachedChild = NewerSourceFinder.combine(
            children_.getList().stream()
                               .map(x -> x.detach(logger))
                               .collect(Collectors.toList()));

        Iterable<Resource> sources = resources_.getList().isEmpty() ?
            null :
            new Iterable<Resource>() {
                @Override
                public Iterator<Resource> iterator() {
                    return new SerialIterator<>(createResourceIterableIterable());
                }
                @Override
                public String toString() {
                    switch (resources_.getList().size()) {
                    case 0:
                        assert false;
                        return "";
                    case 1:
                        return stringValueOfResourceCollection(
                            resources_.getList().iterator().next());
                    default:
                        return '['
                              + String.join(
                                    ", ",
                                    () -> new TransformIterator<>(
                                            resources_.getList().iterator(),
                                            r -> stringValueOfResourceCollection(r)))
                              + ']';
                    }
                }
                private String stringValueOfResourceCollection(ResourceCollection r) {
                    if (r instanceof FileSet) {
                        return "fileset(dir=" + ((FileSet) r).getDir() + ")";
                    } else if (r instanceof FileList) {
                        FileList l = (FileList) r;
                        return "filelist(dir=" + l.getDir(getProject()) +
                               ", files=" + Arrays.asList(l.getFiles(getProject())) + ")";
                    } else {
                        String raw = String.valueOf(r);
                        return raw.isEmpty() ? r.getClass().getName() : raw;
                    }
                }
            };

        return new DetachedDepends(this, sources, selector, detachedChild, absent_, logger);
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

        private Object issuer_;
        private Logger logger_;
        private Absent absent_;

        private Iterable<Resource> sources_;
        private Predicate<File> selector_;
        private NewerSourceFinder child_;

        private boolean absentSignificantly_ = false;
        private List<File> sourceFiles_ = null;
        private Resource newestSource_ = null;

        /**
         *
         * @param sources
         *      a collection of resources depended by files which matches <var>selector</var>.
         *      Can be {@code null} when no directly depended resources are known to this object.
         * @param issuer
         * @param selector
         * @param child
         * @param absent
         * @param logger
         */
        public DetachedDepends(Object issuer, Iterable<Resource> sources,
                Predicate<File> selector, NewerSourceFinder child, Absent absent, Logger logger) {
            issuer_ = issuer;
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
            switch (absent_) {
            case NEW:
                logger_.log(issuer_,
                    "Referred resources " + sources_ + " are missing to be regarded as new",
                    Level.INFO);
                return new FileResource();
            case FAIL:
                logger_.log(issuer_,
                    "Referred resources " + sources_ + " are missing", Level.ERR);
                throw new BuildException();
            case IGNORE:
            default:
                assert false;
                return null;
            }
        }
    }
}
