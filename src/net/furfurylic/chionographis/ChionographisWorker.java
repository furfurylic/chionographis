package net.furfurylic.chionographis;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

final class ChionographisWorker implements Runnable {

    public static class OriginalSource {
        private int index_;
        private URI uri_;
        private String fileName_;
        private long lastModified_;

        public OriginalSource(int index, URI uri, String fileName, long lastModified) {
            index_ = index;
            uri_ = uri;
            fileName_ = fileName;
            lastModified_ = lastModified;
        }

        public OriginalSource() {
            uri_ = null;
        }

        public boolean isPoison() {
            return uri_ == null;
        }

        public int index() {
            return index_;
        }

        public URI uri() {
            return uri_;
        }

        public String fileName() {
            return fileName_;
        }

        public long lastModified() {
            return lastModified_;
        }
    }

    public static interface SupplierX<T, X extends Exception> {
        T get() throws X;
    }

    private Sink sink_;
    private BiConsumer<String, LogLevel> logger_;
    private Map<String, Function<URI, String>> metaFuncMap_;

    private SupplierX<OriginalSource, ? extends InterruptedException> sources_;
    private Consumer<Object> resultSink_;
    private XMLTransfer xfer_;

    /**
     * Constructs a worker.
     *
     * @param sink
     *      a {@link Sink}, which is the destination of the document.
     * @param logger
     *      a logger.
     * @param resolver
     *      a SAX {@link EntityResolver}.
     * @param metaFuncMap
     *      a map which maps processing instruction targets to corresponding meta-information
     *      deduction functions.
     * @param sources
     *      a possibly-blocking queue-polling function for the original sources,
     *      whose exhaustion is signalled by a "poison" object.
     * @param resultSink
     *      a possibly-blocking queue-offering function for the results,
     *      which may be an {@link Integer} or a {@link Throwable},
     *      where former means the successfully-processed count and lattter means an error.
     */
    public ChionographisWorker(
                Sink sink, BiConsumer<String, LogLevel> logger,
                EntityResolver resolver, Map<String, Function<URI, String>> metaFuncMap,
                SupplierX<OriginalSource, ? extends InterruptedException> sources,
                Consumer<Object> resultSink) {
        sink_ = sink;
        logger_ = logger;
        xfer_ = new XMLTransfer(resolver);
        metaFuncMap_ = metaFuncMap;
        sources_ = sources;
        resultSink_ = resultSink;
    }

    @Override
    public void run() {
        try {
            int count = 0;
            for (;;) {
                OriginalSource originalSrc = sources_.get();
                if (originalSrc.isPoison()) {
                    break;
                }

                ++count;

                String systemID = originalSrc.uri().toString();

                logger_.accept("Processing " + systemID, LogLevel.VERBOSE);
                List<XPathExpression> referents = sink_.referents();
                List<String> referredContents;
                Source source;
                if (!referents.isEmpty()) {
                    logger_.accept(
                        "  Referral to the source contents required", LogLevel.DEBUG);
                    Document document = xfer_.parse(new StreamSource(systemID));
                    referredContents = Referral.extract(document, referents);
                    logger_.accept("  Referred source data: "
                        + String.join(", ", referredContents), LogLevel.DEBUG);

                    if (!metaFuncMap_.isEmpty()) {
                        DocumentFragment metas = document.createDocumentFragment();
                        addMetaInformation(metaFuncMap_, originalSrc.uri(), (target, data) ->
                            metas.appendChild(
                                document.createProcessingInstruction(target, data)));
                        Element docElem = document.getDocumentElement();
                        docElem.insertBefore(metas, docElem.getFirstChild());
                    }

                    source = new DOMSource(document, systemID);

                } else {
                    logger_.accept(
                        "  Referral to the source contents not required", LogLevel.DEBUG);
                    referredContents = Collections.emptyList();
                    if (!metaFuncMap_.isEmpty()) {
                        source = new SAXSource(
                            new MetaFilter(null,
                                c -> addMetaInformation(metaFuncMap_, originalSrc.uri(), c)),
                            new InputSource(systemID));
                    } else {
                        source = new StreamSource(systemID);
                    }
                }

                // Do processing.
                Result result = sink_.startOne(originalSrc.index(), originalSrc.fileName(), originalSrc.lastModified(), referredContents);
                if (result != null) {
                    try {
                        xfer_.transfer(source, result);
                        if (Thread.interrupted()) {
                            throw new InterruptedException();
                        }
                    } catch (BuildException | InterruptedException e) {
                        logger_.accept("Aborting processing " + systemID, LogLevel.WARN);
                        sink_.abortOne(result);
                        if (e instanceof FatalityException) {
                            throw new BuildException(e.getCause());
                        }
                    }
                    sink_.finishOne(result);
                }
            }
            resultSink_.accept(Integer.valueOf(count));
        } catch (Throwable e) {
            resultSink_.accept(e);
        }
    }

    private void addMetaInformation(
            Map<String, Function<URI, String>> metaFuncMap,
            URI sourceURI, BiConsumer<String, String> consumer) {
        for (Map.Entry<String, Function<URI, String>> metaFunc : metaFuncMap.entrySet()) {
            String target = metaFunc.getKey();
            String data = metaFunc.getValue().apply(sourceURI);
            logger_.accept("Adding processing instruction; target=" + target + ", data=" + data,
                LogLevel.DEBUG);
            consumer.accept(target, data);
        }
    }

    private static final class MetaFilter extends XMLFilterImpl {

        private static final class HackedSAXException extends RuntimeException {

            private static final long serialVersionUID = 1L;

            public HackedSAXException(SAXException cause) {
                super(cause);
            }

            @Override
            public SAXException getCause() {
                return (SAXException) super.getCause();
            }
        }

        private Consumer<BiConsumer<String, String>> adder_;

        public MetaFilter(XMLReader parent, Consumer<BiConsumer<String, String>> adder) {
            super(parent);
            adder_ = adder;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            super.startElement(uri, localName, qName, atts);
            if (adder_ != null) {
                try {
                    adder_.accept(this::processingInstructionHacked);
                } catch (HackedSAXException e) {
                    throw e.getCause();
                }
                adder_ = null;
            }
        }

        private void processingInstructionHacked(String target, String data) {
            try {
                processingInstruction(target, data);
            } catch (SAXException e) {
                throw new HackedSAXException(e);
            }
        }
    }
}
