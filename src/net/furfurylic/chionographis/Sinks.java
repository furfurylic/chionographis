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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

final class Sinks extends Sink implements Logger {

    private Logger logger_;
    private List<Sink> sinks_;
    private XMLTransfer xfer_ = null;

    /**
     * {@code includes_[i][j]} tells whether {@code sinks_.get(i)} wants the source indexed by
     * {@code j} to be processed.
     *
     * <p>This field is maintained by {@link #preexamineBundle(String[], long[])} method.
     */
    private boolean[][] includes_;

    private Map<Result, List<Sink>> activeSinkMap_;

    public Sinks(Logger logger) {
        logger_ = logger;
        sinks_ = new ArrayList<>();
    }

    @Override
    public void log(Object issuer, String message, Logger.Level level) {
        logger_.log(issuer, message, level);
    }

    @Override
    public void log(Object issuer, String message, Throwable ex, Logger.Level level) {
        logger_.log(issuer, message, ex, level);
    }

    public Transform createTransform() {
        Transform sink = new Transform(logger_);
        sinks_.add(sink);
        return sink;
    }

    public All createAll() {
        All sink = new All(logger_);
        sinks_.add(sink);
        return sink;
    }

    public Snip createSnip() {
        Snip sink = new Snip(logger_);
        sinks_.add(sink);
        return sink;
    }

    public Output createOutput() {
        Output sink = new Output(logger_);
        sinks_.add(sink);
        return sink;
    }

    @Override
    void init(File baseDir, NamespaceContext namespaceContext, boolean force) {
        sinks_.stream().forEach(s -> s.init(baseDir, namespaceContext, force));
    }

    @Override
    List<XPathExpression> referents() {
        return sinks_.stream()
                      .map(s -> s.referents())
                      .flatMap(r -> r.stream())
                      .collect(Collectors.toList());
    }

    @Override
    boolean[] preexamineBundle(String[] originalSrcFileNames, long[] originalSrcLastModifiedTimes) {
        includes_ = IntStream.range(0, sinks_.size())
            .mapToObj(i -> sinks_.get(i))
            .map(s -> s.preexamineBundle(originalSrcFileNames, originalSrcLastModifiedTimes))
            .toArray(boolean[][]::new);

        boolean[] results = new boolean[includes_[0].length];
        IntStream.range(0, results.length)
            .forEach(j -> results[j] = IntStream.range(0, includes_.length)
                                            .anyMatch(i -> includes_[i][j]));
        return results;
    }

    @Override
    void startBundle() {
        if (includes_ == null) {
            sinks_.stream().forEach(Sink::startBundle);
        } else {
            IntStream.range(0, includes_.length)
            .filter(i -> IntStream.range(0, includes_[i].length)
                            .anyMatch(j -> includes_[i][j]))
            .mapToObj(i -> sinks_.get(i))
            .forEach(Sink::startBundle);
        }
        if (activeSinkMap_ == null) {
            activeSinkMap_ = new IdentityHashMap<>();
        } else {
            activeSinkMap_.clear();
        }
    }

    @Override
    Result startOne(int originalSrcIndex, String originalSrcFileName,
            long originalSrcLastModifiedTime, List<String> referredContents) {
        List<Sink> activeSinks = null;
        CompositeResultBuilder builder = new CompositeResultBuilder();
        int i = 0;
        for (int j = 0; j < sinks_.size(); ++j) {
            if (includes_ != null) {
                boolean[] includesOne = includes_[j];
                if (IntStream.range(0, includesOne.length).noneMatch(k -> includesOne[k])) {
                    continue;
                }
            }
            List<String> referredContentsOne =
                referredContents.subList(i, i + sinks_.get(j).referents().size());
            Result result = sinks_.get(j).startOne(
                originalSrcIndex, originalSrcFileName, originalSrcLastModifiedTime, referredContentsOne);
            if (result != null) {
                builder.add(result);
                if (activeSinks == null) {
                    activeSinks = Collections.singletonList(sinks_.get(j));
                } else{
                    if (activeSinks.size() == 1) {
                        Sink s = activeSinks.get(0);
                        activeSinks = new ArrayList<>();
                        activeSinks.add(s);
                    }
                    activeSinks.add(sinks_.get(j));
                }
            }
        }
        Result r = builder.newCompositeResult();
        synchronized (activeSinkMap_) {
            activeSinkMap_.put(r, activeSinks);
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
        } else if (result instanceof CompositeDOMResult) {
            CompositeDOMResult r = (CompositeDOMResult) result;
            DOMSource source = new DOMSource(r.getNode());
            if (xfer_ == null) {
                xfer_ = new XMLTransfer(null);
            }
            // [1, size()): send result by copy
            IntStream.range(1, activeSinks.size()).forEach(i -> {
                xfer_.transfer(source, r.resultOf(i), false);
                activeSinks.get(i).finishOne(r.resultOf(i));
            });
            // [0, 1): send result by move
            xfer_.transfer(source, r.resultOf(0), true);
            activeSinks.get(0).finishOne(r.resultOf(0));
        } else {
            assert result instanceof CompositeSAXResult : result.getClass();
            CompositeSAXResult r = (CompositeSAXResult) result;
            IntStream.range(0, activeSinks.size()).forEach(i -> activeSinks.get(i).finishOne(r.resultOf(i)));
        }
    }

    @Override
    void abortOne(Result result) {
        assert result != null;
        List<Sink> activeSinks;
        synchronized (activeSinkMap_) {
            activeSinks = activeSinkMap_.get(result);
            activeSinkMap_.remove(result);
        }
        assert activeSinks != null;
        if (activeSinks.size() == 1) {
            activeSinks.get(0).abortOne(result);
        } else {
            assert result instanceof CompositeSAXResult : result.getClass();
            CompositeSAXResult r = (CompositeSAXResult) result;
            Optional<RuntimeException> ex =
                IntStream.range(0, activeSinks.size())
                         .mapToObj(i -> Sinks.abortSink(activeSinks.get(i), r.resultOf(i)))
                         .filter(e -> e != null)
                         .findAny();
            if (ex.isPresent()) {
                throw ex.get();
            }
        }
    }

    private static RuntimeException abortSink(Sink sink, Result result) {
        try {
            sink.abortOne(result);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    @Override
    void finishBundle() {
        if (includes_ == null) {
            sinks_.stream().forEach(Sink::finishBundle);
        } else {
            IntStream.range(0, includes_.length)
                     .filter(i -> IntStream.range(0, includes_[i].length)
                                           .anyMatch(j -> includes_[i][j]))
                     .mapToObj(i -> sinks_.get(i))
                     .forEach(Sink::finishBundle);
        }
    }

    private static class CompositeSAXResult extends SAXResult {

        List<Result> results_;

        public CompositeSAXResult(ContentHandler handler, List<Result> results) {
            super(handler);
            results_ = results;
        }

        Result resultOf(int index) {
            return results_.get(index);
        }
    }

    private static class CompositeDOMResult extends DOMResult {

        List<Result> results_;

        public CompositeDOMResult(Node node, List<Result> results) {
            super(node);
            results_ = results;
        }

        Result resultOf(int index) {
            return results_.get(index);
        }
    }

    private static final class CompositeResultBuilder {

        private List<Result> results_ = new ArrayList<>();

        public CompositeResultBuilder() {
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
                return new CompositeDOMResult(new XMLTransfer(null).newDocument(), results_);
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
                return new CompositeSAXResult(new CompositeHandler(contentHandlers, lexicalHandlers), results_);
            } catch (TransformerConfigurationException e) {
                throw new FatalityException(e);
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
            public void startDTD(String name, String publicId, String systemId) throws SAXException {
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
            public void endElement(String uri, String localName, String qName) throws SAXException {
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
            public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
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


