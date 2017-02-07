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
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongFunction;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPathExpression;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;
import org.apache.tools.ant.types.Resource;
import org.w3c.dom.DOMException;
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

    private Location location_;
    private boolean failOnNonfatalError_;

    private int index_;
    private URI uri_;
    private String fileName_;
    private LongFunction<Resource> finder_;

    private IntSupplier isOK_;

    private Sink sink_;
    private Logger logger_;
    private List<Map.Entry<String, Function<URI, String>>> metaFuncs_;
    private XMLTransfer xfer_;

    /**
     * Sole constructor.
     *
     * @param failOnNonfatalError
     *      whether the execution should result an exception thrown
     *      if nonfatal error occurs.
     * @param index
     *      an opaque index of the original source.
     * @param uri
     *      the URI of the original source.
     * @param fileName
     *      the file name of the original source.
     * @param finder
     *      the last modified time of the original source, which is the number of milliseconds
     *      from the epoch.
     * @param sink
     *      a sink which receives the document.
     * @param logger
     *      a logger.
     * @param metaFuncs
     *      a key-value pairs of the meta-information name and the function which deduces
     *      the meta-information value from the URI of the original source URI.
     * @param xfer
     *      an object which transfers XML documents.
     * @param isOK
     *      a function which tells whether the execution is go (1) or no-go (0).
     * @param location
     *      the location embedded into exceptions thrown, which can be {@code null}.
     */
    public ChionographisWorker(
            boolean failOnNonfatalError,
            int index, URI uri, String fileName, LongFunction<Resource> finder,
            Sink sink, Logger logger,
            List<Map.Entry<String, Function<URI, String>>> metaFuncs, XMLTransfer xfer,
            IntSupplier isOK, Location location) {
        location_ = location;
        failOnNonfatalError_ = failOnNonfatalError;
        index_ = index;
        uri_ = uri;
        fileName_ = fileName;
        finder_ = finder;
        sink_ = sink;
        logger_ = logger;
        metaFuncs_ = metaFuncs;
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
            logger_.log(null, "Processing " + systemID, Level.VERBOSE);

            List<XPathExpression> referents = sink_.referents();
            List<String> referredContents;
            Source source;
            if (!referents.isEmpty()) {
                Document document = xfer_.parse(new StreamSource(systemID), location_);

                if (!metaFuncs_.isEmpty()) {
                    DocumentFragment metas = document.createDocumentFragment();
                    addMetaInformation((target, data) ->
                        metas.appendChild(document.createProcessingInstruction(target, data)));
                    Element docElem = document.getDocumentElement();
                    docElem.insertBefore(metas, docElem.getFirstChild());
                }

                referredContents = Referral.extract(document, referents);
                logger_.log(null, "Referred source data: "
                    + String.join(", ", referredContents), Level.DEBUG);

                if (isOK_.getAsInt() == 0) {
                    return 0;
                }

                source = new DOMSource(document, systemID);

            } else {
                referredContents = Collections.emptyList();
                if (!metaFuncs_.isEmpty()) {
                    source = new SAXSource(
                        new MetaFilter(null, this::addMetaInformation),
                        new InputSource(systemID));
                } else {
                    source = new StreamSource(systemID);
                }
            }

            Result result = sink_.startOne(index_, fileName_, finder_, referredContents);
            if (result == null) {
                return 1;
            }

            boolean toFinish = false;
            try {
                xfer_.transfer(source, result, location_);
                toFinish = true;
            } catch (DOMException | NonfatalBuildException e) {
                return handleRecoverableFailure(e);
            } finally {
                if (!toFinish) {
                    // sink_.startOne() succeeded but we can't proceed to sink_.finishOne()
                    // -> we shall try to call sink_.abort()
                    logger_.log(null, "Aborting processing " + systemID, Level.WARN);
                    sink_.abortOne(result);
                }
            }

            if (isOK_.getAsInt() == 0) {
                logger_.log(null, "Aborting processing " + systemID, Level.VERBOSE);
                sink_.abortOne(result);
                return 0;
            } else {
                sink_.finishOne(result);
                return 1;
            }

        } catch (DOMException | NonfatalBuildException e) {
            // sink_.startOne() has not been called yet or sink_.finishOne() has failed
            // -> we need not try to call sink_.abort()
            logger_.log(null, "Failed to process " + systemID, Level.WARN);
            return handleRecoverableFailure(e);
        }
    }

    private int handleRecoverableFailure(RuntimeException e) {
        if (failOnNonfatalError_) {
            throw new BuildException(
                "Nonfatal error occurred and \"failOnNonfatalError\" specified", e, location_);
        } else {
            logger_.log(null, e, "  Cause: ", Level.INFO, Level.VERBOSE);
                // "Aborting processing" or "Failed to process" shall precede this
            return 0;
        }
    }

    private <X extends Throwable> void addMetaInformation(ProcessingInstructionPost<X> consumer)
            throws X {
        for (Map.Entry<String, Function<URI, String>> metaFunc : metaFuncs_) {
            String target = metaFunc.getKey();
            String data = metaFunc.getValue().apply(uri_);
            logger_.log(null,
                "Adding a processing instruction: target=" + target + ", data=" + data,
                Level.DEBUG);
            consumer.processingInstrcution(target, data);
        }
    }

    @FunctionalInterface
    private static interface ProcessingInstructionPost<X extends Throwable> {
        void processingInstrcution(String target, String data) throws X;
    }

    @FunctionalInterface
    private static interface ProcessingInstructionAdder<X extends Throwable> {
        void add(ProcessingInstructionPost<X> post) throws X;
    }

    private static final class MetaFilter extends XMLFilterImpl {

        private ProcessingInstructionAdder<SAXException> adder_;

        public MetaFilter(XMLReader parent, ProcessingInstructionAdder<SAXException> adder) {
            super(parent);
            adder_ = adder;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            super.startElement(uri, localName, qName, atts);
            if (adder_ != null) {
                adder_.add(this::processingInstruction);
                adder_ = null;
            }
        }
    }
}
