/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import net.furfurylic.chionographis.Logger.Level;

/**
 * A class for worker objects which reads an original source and send it to a sink.
 */
final class ChionographisWorker {
    /**
     * A logger type which resembles {@link Logger},
     * but for which the issuer object is already bound externally.
     */
    public static interface BoundLogger {
        /**
         * Logs a message with the given priority.
         *
         * @param message
         *      the message to be logged, which shall not be {@code null}.
         * @param level
         *      the priority of the log entry, which shall not be {@code null}.
         */
        void log(String message, Level level);

        /**
         * Logs a exception with the given priority.
         *
         * @param ex
         *      the exception object to be logged, which shall not be {@code null}.
         * @param heading
         *      the heading added to the stack trace of {@code ex}.
         *      This can be an empty string, but shall not be {@code null}.
         * @param headingLevel
         *      the log priority of the first line of the stack trace of {@code ex},
         *      which shall not be {@code null}.
         * @param bodyLevel
         *      the log priority of the second and subsequent lines of the stack trace of
         *      {@code ex}, which shall not be {@code null}.
         */
        void log(Throwable ex, String heading, Level headingLevel, Level bodyLevel);
    }

    private boolean failOnNonfatalError_;

    private int index_;
    private URI uri_;
    private String fileName_;
    private long lastModified_;

    private IntSupplier isOK_;

    private Sink sink_;
    private BoundLogger logger_;
    private Map<String, Function<URI, String>> metaFuncMap_;
    private XMLTransfer xfer_;

    /**
     * Sole constructor.
     *
     * @param index
     *      an opaque index of the original source.
     * @param uri
     *      the URI of the original source.
     * @param fileName
     *      the file name of the original source.
     * @param lastModified
     *      the last modified time of the original source, which is the number of milliseconds
     *      from the epoch.
     * @param sink
     *      a sink which receives the document.
     * @param logger
     *      a logger.
     * @param metaFuncMap
     *      a key-value pairs of the meta-information name and the function which deduces
     *      the meta-information value from the URI of the original source URI.
     * @param xfer
     *      an object which transfers XML documents.
     * @param isOK
     *      a function which tells whether the execution is go (1) or no-go (0).
     */
    public ChionographisWorker(
            boolean failOnNonfatalError,
            int index, URI uri, String fileName, long lastModified,
            Sink sink, BoundLogger logger,
            Map<String, Function<URI, String>> metaFuncMap, XMLTransfer xfer,
            IntSupplier isOK) {
        failOnNonfatalError_ = failOnNonfatalError;
        index_ = index;
        uri_ = uri;
        fileName_ = fileName;
        lastModified_ = lastModified;
        sink_ = sink;
        logger_ = logger;
        metaFuncMap_ = metaFuncMap;
        xfer_ = xfer;
        isOK_ = isOK;
    }

    /**
     * Executes the work.
     *
     * @return
     *      1 if successful, 0 otherwise.
     */
    public int run() {
        if (isOK_.getAsInt() == 0) {
            return 0;
        }

        String systemID = null;
        try {
            systemID = uri_.toString();
            logger_.log("Processing " + systemID, Level.VERBOSE);

            List<XPathExpression> referents = sink_.referents();
            List<String> referredContents;
            Source source;
            if (!referents.isEmpty()) {
                Document document = xfer_.parse(new StreamSource(systemID));
                referredContents = Referral.extract(document, referents);
                logger_.log("Referred source data: "
                    + String.join(", ", referredContents), Level.DEBUG);

                if (!metaFuncMap_.isEmpty()) {
                    DocumentFragment metas = document.createDocumentFragment();
                    addMetaInformation(metaFuncMap_, uri_, (target, data) ->
                        metas.appendChild(
                            document.createProcessingInstruction(target, data)));
                    Element docElem = document.getDocumentElement();
                    docElem.insertBefore(metas, docElem.getFirstChild());
                }

                if (isOK_.getAsInt() == 0) {
                    return 0;
                }

                source = new DOMSource(document, systemID);

            } else {
                referredContents = Collections.emptyList();
                if (!metaFuncMap_.isEmpty()) {
                    source = new SAXSource(
                        new MetaFilter(null,
                            c -> addMetaInformation(metaFuncMap_, uri_, c)),
                        new InputSource(systemID));
                } else {
                    source = new StreamSource(systemID);
                }
            }

            Result result = sink_.startOne(index_, fileName_, lastModified_, referredContents);
            if (result == null) {
                return 1;
            }

            try {
                xfer_.transfer(source, result);
            } catch (BuildException e) {
                logger_.log("Aborting processing " + systemID, Level.WARN);
                if (!failOnNonfatalError_) {
                    logCause(e);
                }
                try {
                    sink_.abortOne(result);
                } catch (FatalityException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new FatalityException(e);
                }
                if (failOnNonfatalError_) {
                    throw new FatalityException(e);
                } else {
                    return 0;
                }
            }
            if (isOK_.getAsInt() == 0) {
                return 0;
            }

            sink_.finishOne(result);
            return 1;

        } catch (FatalityException e) {
            throw e;
        } catch (Exception e) {
            logger_.log("Failed to process " + systemID, Level.WARN);
            if (failOnNonfatalError_) {
                if (e instanceof RuntimeException) {
                    throw e;
                } else {
                    throw new FatalityException(e);
                }
            } else {
                logCause(e);
                return 0;
            }
        }
    }

    private void logCause(Exception e) {
        if (!(e instanceof ChionographisBuildException) ||
            !((ChionographisBuildException) e).isLoggedAlready()) {
            logger_.log(e, "  Cause: " + e, Level.INFO, Level.VERBOSE);
        }
    }

    private void addMetaInformation(
            Map<String, Function<URI, String>> metaFuncMap,
            URI sourceURI, BiConsumer<String, String> consumer) {
        for (Map.Entry<String, Function<URI, String>> metaFunc : metaFuncMap.entrySet()) {
            String target = metaFunc.getKey();
            String data = metaFunc.getValue().apply(sourceURI);
            logger_.log("Adding a processing instruction: target=" + target + ", data=" + data,
                Level.DEBUG);
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
