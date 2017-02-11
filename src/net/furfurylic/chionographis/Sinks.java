/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;
import org.apache.tools.ant.types.Resource;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * Represents a composite of {@code Sink} objects.
 */
final class Sinks extends Sink {

    private static final String ABORT_DOOMED =
            "Failed to process at least one source to give up all";

    private Location location_;
    private Assemblage<Sink> sinks_ = new Assemblage<>();

    /**
     * {@code includes_[i][j]} tells whether {@code sinks_.get(i)} wants the source indexed by
     * {@code j} to be processed.
     *
     * <p>This field is maintained by {@link #preexamineBundle(String[], long[])} method.
     */
    private boolean[][] includes_;

    /**
     * A map which maps a TrAX Result object returned {@link #startOne(int, String, long, List)}
     * of this object to {@link Sink} objects responsive to it.
     *
     * <p>This map is based on identity of the keys (that is, mapping by keys' identities).</p>
     */
    private Map<Result, List<Sink>> activeSinkMap_;

    /**
     * Sole constructor.
     *
     * @param location
     *      the location embedded into exceptions thrown, which can be {@code null}.
     */
    public Sinks(Location location) {
        location_ = location;
    }

    private List<Sink> sinks() {
        return sinks_.getList();
    }

    /**
     * Adds a {@link Transform} filter into this composite.
     *
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     *
     * @return
     *      a {@link Transform} filter object.
     */
    public Transform createTransform() {
        Transform sink = new Transform();
        sinks_.add(sink);
        return sink;
    }

    /**
     * Adds an {@link All} filter into this composite.
     *
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     *
     * @return
     *      an {@link All} filter object.
     */
    public All createAll() {
        All sink = new All();
        sinks_.add(sink);
        return sink;
    }

    /**
     * Adds a {@link Snip} filter into this composite.
     *
     * @param expander
     *      an object which expands properties in a text, which shall not be {@code null}.
     *
     * @return
     *      a {@link Snip} filter object.
     */
    public Snip createSnip() {
        Snip sink = new Snip();
        sinks_.add(sink);
        return sink;
    }

    /**
     * Adds an {@link Output} sink into this composite.
     *
     * @return
     *      an {@link Output} sink object.
     */
    public Output createOutput() {
        Output sink = new Output();
        sinks_.add(sink);
        return sink;
    }

    public boolean isEmpty() {
        return sinks_.isEmpty();
    }

    @Override
    void init(File baseDir, NamespaceContext namespaceContext, Logger logger,
            boolean force, boolean dryRun) {
        sinks().stream().forEach(s -> s.init(baseDir, namespaceContext, logger, force, dryRun));
    }

    @Override
    List<XPathExpression> referents() {
        return sinks().stream()
                      .map(s -> s.referents())
                      .flatMap(List::stream)
                      .collect(Collectors.toList());
    }

    @Override
    boolean[] preexamineBundle(String[] origSrcFileNames, LongFunction<Resource>[] finders) {
        includes_ = sinks().stream()
                           .map(s -> s.preexamineBundle(origSrcFileNames, finders))
                           .toArray(boolean[][]::new);

        boolean[] results = new boolean[includes_[0].length];
        IntStream.range(0, results.length)
                 .forEach(j -> results[j] = IntStream.range(0, includes_.length)
                                                     .anyMatch(i -> includes_[i][j]));
        return results;
    }

    @Override
    void startBundle() {
        forEachIncludedSink(Sink::startBundle);
        if (activeSinkMap_ == null) {
            activeSinkMap_ = new IdentityHashMap<>();
        } else {
            activeSinkMap_.clear();
        }
    }

    private void forEachIncludedSink(Consumer<Sink> f) {
        if (includes_ == null) {
            sinks().stream().forEach(f);
        } else {
            IntStream.range(0, includes_.length)
                     .filter(i -> IntStream.range(0, includes_[i].length)
                                           .anyMatch(j -> includes_[i][j]))
                     .mapToObj(i -> sinks().get(i))
                     .forEach(f);
        }
    }

    @Override
    Result startOne(int origSrcIndex, String origSrcFileName,
            LongFunction<Resource> finder, List<String> referredContents) {
        Assemblage<Sink> activeSinks = new Assemblage<>();
        CompositeResultBuilder builder = new CompositeResultBuilder(location_);
        int j = 0;
        try {
            int i = 0;
            for (; j < sinks().size(); ++j) {
                // Grab referred contents for this sink.
                List<String> referredContentsOne =
                    referredContents.subList(i, i + sinks().get(j).referents().size());
                i += sinks().get(j).referents().size();
                // If the sink does not include sources at all, skip it.
                if (includes_ != null) {
                    boolean[] includesOne = includes_[j];
                    if (IntStream.range(0, includesOne.length).noneMatch(k -> includesOne[k])) {
                        continue;
                    }
                }
                // Open the result of the sink.
                Result result = sinks().get(j).startOne(
                    origSrcIndex, origSrcFileName, finder, referredContentsOne);
                if (result != null) {
                    // First, we populate activeSinks.
                    activeSinks.add(sinks().get(j));
                    // Second, we populate builder.
                    builder.add(result);    // We don't assume if any exception thrown here
                                            // it is a recoverable situation.
                }
            }
        } catch (RuntimeException e) {
            // We shall abort already-started results.
            Result r = builder.newCompositeResult();
            if (r != null) {
                if (abort(r, activeSinks.getList()) != null) {
                    throw new BuildException(ABORT_DOOMED, e, location_);
                } else {
                    throw e;
                }
            } else {
                throw e;
            }
        }

        Result r = builder.newCompositeResult();
        if (r != null) {
            synchronized (activeSinkMap_) {
                activeSinkMap_.put(r, activeSinks.getList());
            }
        }
        return r;
    }

    @Override
    void finishOne(Result result) {
        assert result != null;
        List<Sink> activeSinks;
        synchronized (activeSinkMap_) {
            activeSinks = activeSinkMap_.get(result);
            activeSinkMap_.remove(result);
        }
        assert activeSinks != null;

        if (activeSinks.size() == 1) {
            activeSinks.get(0).finishOne(result);
        } else {
            assert result instanceof CompositeResult;
            try {
                ((CompositeResult) result).results().finish(activeSinks);
            } catch (BuildException e) {
                if (e.getLocation() == null) {
                    e.setLocation(location_);
                }
                throw e;
            }
        }
    }

    @Override
    Sink abortOne(Result result) {
        assert result != null;
        List<Sink> activeSinks;
        synchronized (activeSinkMap_) {
            activeSinks = activeSinkMap_.get(result);
            activeSinkMap_.remove(result);
        }
        return abort(result, activeSinks);
    }

    private static Sink abort(Result result, List<Sink> sinks) {
        assert sinks != null;
        assert !sinks.isEmpty();
        if (sinks.size() == 1) {
            return sinks.get(0).abortOne(result);
        } else {
            assert result instanceof CompositeResult;
            return ((CompositeResult) result).results().abort(sinks);
        }
    }

    @Override
    void finishBundle() {
        forEachIncludedSink(Sink::finishBundle);
    }

    /** A collection of TrAX Results. */
    private static abstract class Results {
        private List<Result> results_;

        public Results(List<Result> results) {
            results_ = results;
        }

        public abstract void finish(List<Sink> sinks);

        /**
         * Does the maximum effort to abort all results.
         *
         * @param sinks
         *      a list of {@link Sink}s, which shall be coincident with this object
         *      and shall not be neither {@code null} nor empty.
         *
         * @throws RuntimeException
         *      if one of {@code sinks} throws a {@link RuntimeException}.
         *      This situation should be considered as unrecoverable.
         */
        public final Sink abort(List<Sink> sinks) {
            assert sinks.size() == results_.size();
            return abortRange(IntStream.range(0, sinks.size()), sinks);
        }

        protected final Sink abortRange(IntStream indices, List<Sink> sinks) {
            return indices.mapToObj(i -> sinks.get(i).abortOne(results_.get(i)))
                          .filter(e -> e != null)
                          .reduce((e1, e2) -> e1)
                          .orElse(null);
        }

        protected final void abortRangeAndThrow(IntStream indices, List<Sink> sinks,
                RuntimeException cause) {
            if (abortRange(indices, sinks) != null) {
                throw new BuildException(ABORT_DOOMED, cause);
            } else {
                throw cause;
            }
        }

        protected final List<Result> asList() {
            return results_;
        }
    }

    /** An interface of holders of one {@link Results} object. */
    private static interface CompositeResult {
        Results results();
    }

    private static class CompositeSAXResult extends SAXResult implements CompositeResult {

        private Results results_;

        public CompositeSAXResult(ContentHandler handler, List<Result> results) {
            super(handler);
            results_ = new Results(results) {
                @Override
                public void finish(List<Sink> sinks) {
                    List<Result> rs = asList();
                    int i = 0;
                    try {
                        while (i < sinks.size()) {
                            sinks.get(i).finishOne(rs.get(i));
                            ++i;
                        }
                    } catch (RuntimeException e) {
                        abortRangeAndThrow(IntStream.range(i + 1, sinks.size()), sinks, e);
                    }
                }
            };
        }

        @Override
        public Results results() {
            return results_;
        }
    }

    private static class CompositeDOMResult extends DOMResult implements CompositeResult {

        private Location location_;
        private Results results_;

        public CompositeDOMResult(Node node, List<Result> results, Location location) {
            super(node);
            location_ = location;
            results_ = new Results(results) {
                @Override
                public void finish(List<Sink> sinks) {
                    List<Result> rs = asList();

                    // Search a real DOMResult
                    // Those which getNode() == null have high priority
                    OptionalInt realDOM = IntStream.range(0, sinks.size())
                        .filter(i -> rs.get(i) instanceof DOMResult)
                        .reduce((i, j) -> (((DOMResult) rs.get(j)).getNode() == null) ? j : i);
                    assert realDOM.isPresent();
                    int j = realDOM.getAsInt();

                    XMLTransfer xfer = XMLTransfer.getDefault();
                    DOMSource source = new DOMSource(getNode());

                    // For indices other than j, send the result by copy
                    int i = 0;
                    try {
                        while (i < sinks.size()) {
                            if (i != j) {
                                xfer.transfer(source, rs.get(i), false, location_);
                                ++i;
                                sinks.get(i - 1).finishOne(rs.get(i - 1));
                            } else {
                                ++i;
                            }
                        }
                    } catch (RuntimeException e) {
                        // Sinks which have not been finished shall be aborted
                        abortRangeAndThrow(
                            IntStream.concat(IntStream.range(i, sinks.size()), IntStream.of(j))
                                     .distinct(),
                            sinks, e);
                    }

                    // For the index j, send the result by move
                    xfer.transfer(source, rs.get(j), true, location_);
                    sinks.get(j).finishOne(rs.get(j));
                }
            };
        }

        @Override
        public Results results() {
            return results_;
        }
    }

    private static final class CompositeResultBuilder {

        private Location location_;
        private List<Result> results_ = new ArrayList<>();

        public CompositeResultBuilder(Location location) {
            location_ = location;
        }

        public void add(Result result) {
            assert result != null;
            results_.add(result);
        }

        public Result newCompositeResult() {
            if (results_.isEmpty()) {
                return null;
            }
            if (results_.size() == 1) {
                return results_.get(0);
            }
            if (results_.stream().anyMatch(r -> r instanceof DOMResult)) {
                return new CompositeDOMResult(
                        XMLTransfer.getDefault().newDocument(location_), results_, location_);
            }

            try {
                List<ContentHandler> contentHandlers = new ArrayList<>();
                List<LexicalHandler> lexicalHandlers = new ArrayList<>();
                SAXTransformerFactory tfac = null;
                for (Result result : results_) {
                    if (result instanceof SAXResult) {
                        SAXResult saxResult = (SAXResult) result;
                        contentHandlers.add(saxResult.getHandler());
                        if (saxResult.getLexicalHandler() != null) {
                            lexicalHandlers.add(saxResult.getLexicalHandler());
                        } else if (saxResult.getHandler() instanceof LexicalHandler) {
                            lexicalHandlers.add((LexicalHandler) saxResult.getHandler());
                        }
                    } else {
                        if (tfac == null) {
                            tfac = (SAXTransformerFactory) TransformerFactory.newInstance();
                        }
                        TransformerHandler identity = tfac.newTransformerHandler();
                        identity.setResult(result);
                        contentHandlers.add(identity);
                        lexicalHandlers.add(identity);
                    }
                }
                return new CompositeSAXResult(
                    new CompositeHandler(contentHandlers, lexicalHandlers), results_);
            } catch (TransformerConfigurationException e) {
                throw new BuildException(e);
            }
        }

        private static class CompositeHandler implements ContentHandler, LexicalHandler {

            private List<ContentHandler> contentHandlers_;
            private List<LexicalHandler> lexicalHandlers_;

            public CompositeHandler(List<ContentHandler> contentHandlers,
                    List<LexicalHandler> lexicalHandlers) {
                contentHandlers_ = contentHandlers;
                lexicalHandlers_ = lexicalHandlers;
            }

            @Override
            public void startDTD(String name, String publicId, String systemId)
                    throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.startDTD(name, publicId, systemId);
                }
            }

            @Override
            public void endDTD() throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.endDTD();
                }
            }

            @Override
            public void startEntity(String name) throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.startEntity(name);
                }
            }

            @Override
            public void endEntity(String name) throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.endEntity(name);
                }
            }

            @Override
            public void startCDATA() throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.startCDATA();
                }
            }

            @Override
            public void endCDATA() throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.endCDATA();
                }
            }

            @Override
            public void comment(char[] ch, int start, int length) throws SAXException {
                for (LexicalHandler handler : lexicalHandlers_) {
                    handler.comment(ch, start, length);
                }
            }

            @Override
            public void setDocumentLocator(Locator locator) {
                for (ContentHandler handler : contentHandlers_) {
                    handler.setDocumentLocator(locator);
                }
            }

            @Override
            public void startDocument() throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.startDocument();
                }
            }

            @Override
            public void endDocument() throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.endDocument();
                }
            }

            @Override
            public void startPrefixMapping(String prefix, String uri) throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.startPrefixMapping(prefix, uri);
                }
            }

            @Override
            public void endPrefixMapping(String prefix) throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.endPrefixMapping(prefix);
                }
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts)
                    throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.startElement(uri, localName, qName, atts);
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName)
                    throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.endElement(uri, localName, qName);
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.characters(ch, start, length);
                }
            }

            @Override
            public void ignorableWhitespace(char[] ch, int start, int length)
                    throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.ignorableWhitespace(ch, start, length);
                }
            }

            @Override
            public void processingInstruction(String target, String data) throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.processingInstruction(target, data);
                }
            }

            @Override
            public void skippedEntity(String name) throws SAXException {
                for (ContentHandler handler : contentHandlers_) {
                    handler.skippedEntity(name);
                }
            }
        }
    }
}
