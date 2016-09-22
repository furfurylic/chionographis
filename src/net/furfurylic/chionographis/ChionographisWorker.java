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

    private boolean failOnNonfatalError_;

    private int index_;
    private URI uri_;
    private String fileName_;
    private long lastModified_;

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
     * @param lastModified
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
     */
    public ChionographisWorker(
            boolean failOnNonfatalError,
            int index, URI uri, String fileName, long lastModified,
            Sink sink, Logger logger,
            List<Map.Entry<String, Function<URI, String>>> metaFuncs, XMLTransfer xfer,
            IntSupplier isOK) {
        failOnNonfatalError_ = failOnNonfatalError;
        index_ = index;
        uri_ = uri;
        fileName_ = fileName;
        lastModified_ = lastModified;
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
                Document document = xfer_.parse(new StreamSource(systemID));

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

            Result result = sink_.startOne(index_, fileName_, lastModified_, referredContents);
            if (result == null) {
                return 1;
            }

            try {
                xfer_.transfer(source, result);
            } catch (NonfatalBuildException e) {
                logger_.log(null, "Aborting processing " + systemID, Level.WARN);
                if (!failOnNonfatalError_) {
                    logCause(e);
                }
                try {
                    sink_.abortOne(result);
                } catch (NonfatalBuildException ex) {
                    throw new BuildException(ex);
                } catch (BuildException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new BuildException(e);
                }
                if (failOnNonfatalError_) {
                    throw new BuildException(e);
                } else {
                    return 0;
                }
            }
            if (isOK_.getAsInt() == 0) {
                return 0;
            }

            sink_.finishOne(result);
            return 1;

        } catch (Exception e) {
            if ((e instanceof BuildException) && !(e instanceof NonfatalBuildException)) {
                throw (BuildException) e;
            }
            logger_.log(null, "Failed to process " + systemID, Level.WARN);
            if (failOnNonfatalError_) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new BuildException(e);
                }
            } else {
                logCause(e);
                return 0;
            }
        }
    }

    private void logCause(Exception e) {
        if (!(e instanceof NonfatalBuildException) ||
            !((NonfatalBuildException) e).isLogged()) {
            logger_.log(null, e, "  Cause: " + e, Level.INFO, Level.VERBOSE);
        }
    }

    private void addMetaInformation(ProcessingInstructionPost consumer) throws SAXException {
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
    interface ProcessingInstructionPost {
        void processingInstrcution(String target, String data) throws SAXException;
    }

    @FunctionalInterface
    interface ProcessingInstructionAdder {
        void add(ProcessingInstructionPost post) throws SAXException;
    }

    private static final class MetaFilter extends XMLFilterImpl {

        private ProcessingInstructionAdder adder_;

        public MetaFilter(XMLReader parent, ProcessingInstructionAdder adder) {
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
