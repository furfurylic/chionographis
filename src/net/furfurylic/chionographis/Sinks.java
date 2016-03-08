/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.xml.namespace.NamespaceContext;
import javax.xml.transform.Result;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

final class Sinks extends Sink implements SinkDriver, Logger {

    private Logger logger_;
    private List<Sink> sinks_;

    /**
     * {@code includes_[i][j]} tells whether {@code sinks_.get(i)} wants the source indexed by
     * {@code j} to be processed.
     *
     * <p>This field is maintained by {@link #init(File, URI[], String[], Set)} method.
     */
    private boolean[][] includes_;

    /**
     * Sinks which receives the resulted document of which processing is undergoing.
     *
     * <p>This field is maintained by {@link #startOne(int, String)} method.
     */
    private List<Sink> activeSinks_;

    private int[] referentCounts_;

    public Sinks(Logger logger) {
        logger_ = logger;
        sinks_ = new ArrayList<>();
    }

    @Override
    public void log(Object issuer, String message, LogLevel level) {
        logger_.log(issuer, message, level);
    }

    @Override
    public void log(Object issuer, String message, Throwable ex, LogLevel level) {
        logger_.log(issuer, message, ex, level);
    }

    @Override
    public Transform createTransform() {
        Transform sink = new Transform(logger_);
        sinks_.add(sink);
        return sink;
    }

    @Override
    public All createAll() {
        All sink = new All(logger_);
        sinks_.add(sink);
        return sink;
    }

    @Override
    public Snip createSnip() {
        Snip sink = new Snip(logger_);
        sinks_.add(sink);
        return sink;
    }

    @Override
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
    boolean[] preexamineBundle(URI[] originalSrcURIs, String[] originalSrcFileNames,
                Set<URI> additionalURIs) {
        includes_ = IntStream.range(0, sinks_.size())
            .mapToObj(i -> sinks_.get(i))
            .map(s -> s.preexamineBundle(originalSrcURIs, originalSrcFileNames, additionalURIs))
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
    }

    @Override
    List<XPathExpression> referents(int originalSrcIndex, URI originalSrcURI, String originalSrcFileName) {
        prepareActiveSinks(originalSrcIndex);
        referentCounts_ = null;
        List<List<XPathExpression>> referents = activeSinks_.stream()
                .map(s -> s.referents(originalSrcIndex, originalSrcURI, originalSrcFileName))
                .collect(Collectors.toList());
        if (referents.stream().noneMatch(r -> !r.isEmpty())) {
            return Collections.<XPathExpression>emptyList();
        }
        referentCounts_ = referents.stream()
            .mapToInt(r -> (r.isEmpty() ? 0 : r.size())).toArray();
        List<XPathExpression> expressions = referents.stream()
            .filter(r -> !r.isEmpty())
            .flatMap(r -> r.stream())
            .collect(Collectors.toList());
        return expressions;
    }

    private void prepareActiveSinks(int originalSrcIndex) {
        if ((includes_ == null) || (originalSrcIndex < 0)) {
            activeSinks_ = sinks_;
        } else {
            activeSinks_ = IntStream.range(0, includes_.length)
                .filter(i -> includes_[i][originalSrcIndex])
                .mapToObj(i -> sinks_.get(i))
                .collect(Collectors.toList());
        }
    }

    @Override
    Result startOne(int originalSrcIndex, URI originalSrcURI, String originalSrcFileName,
            List<String> referredContents) {
        if (activeSinks_ == null) {
            prepareActiveSinks(originalSrcIndex);
        }
        if (activeSinks_.isEmpty()) {
            return null;
        } else if (activeSinks_.size() == 1) {
            return activeSinks_.get(0).startOne(
                originalSrcIndex, originalSrcURI, originalSrcFileName, referredContents);
        } else {
            CompositeHandlerBuilder builder = new CompositeHandlerBuilder();
            int i = 0;
            for (int j = 0; j < activeSinks_.size(); ++j) {
                List<String> referredContentsOne;
                if (referentCounts_ != null) {
                    referredContentsOne = referredContents.subList(i, i + referentCounts_[j]);
                    i += referentCounts_[j];
                } else {
                    referredContentsOne = Collections.emptyList();
                }
                Result result = activeSinks_.get(j).startOne(
                    originalSrcIndex, originalSrcURI, originalSrcFileName, referredContentsOne);
                if (result != null) {
                    builder.add(result);
                }
            }
            if (builder.count() > 0) {
                return new SAXResult(builder.newCompositeHandler());
            } else {
                return null;
            }
        }
    }

    @Override
    void finishOne() {
        activeSinks_.stream().forEach(Sink::finishOne);
    }

    @Override
    void abortOne() {
        Optional<RuntimeException> ex = activeSinks_.stream()
            .map(s -> { try { s.abortOne(); return null; } catch (RuntimeException e) { return e; } })
            .filter(e -> e != null)
            .findAny();
        if (ex.isPresent()) {
            throw ex.get();
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

    private static final class CompositeHandlerBuilder {

        public interface CompositeHandler extends ContentHandler, LexicalHandler {
        }

        private ArrayList<ContentHandler> contentHandlers_ = new ArrayList<>();
        private ArrayList<LexicalHandler> lexicalHandlers_ = new ArrayList<>();

        public CompositeHandlerBuilder() {
        }

        public void add(Result result) {
            try {
                SAXTransformerFactory tfac = (SAXTransformerFactory) TransformerFactory.newInstance();
                TransformerHandler identity = tfac.newTransformerHandler();
                identity.setResult(result);
                contentHandlers_.add(identity);
                lexicalHandlers_.add(identity);
            } catch (TransformerConfigurationException e) {
                throw new BuildException(e);
            }
        }

        public int count() {
            return contentHandlers_.size();
        }

        public CompositeHandler newCompositeHandler() {
            return new CompositeHandlerImpl(contentHandlers_, lexicalHandlers_);
        }

        private static class CompositeHandlerImpl implements CompositeHandler {

            private List<ContentHandler> contentHandlers_;
            private List<LexicalHandler> lexicalHandlers_;

            public CompositeHandlerImpl(List<ContentHandler> contentHandlers,
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


