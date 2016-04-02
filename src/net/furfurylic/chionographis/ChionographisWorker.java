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
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import net.furfurylic.chionographis.Logger.Level;

final class ChionographisWorker {

    private int index_;
    private URI uri_;
    private String fileName_;
    private long lastModified_;

    private Sink sink_;
    private BiConsumer<String, Logger.Level> logger_;
    private Map<String, Function<URI, String>> metaFuncMap_;

    private XMLTransfer xfer_;

    public ChionographisWorker(
                int index, URI uri, String fileName, long lastModified,
                Sink sink, BiConsumer<String, Level> logger,
                Map<String, Function<URI, String>> metaFuncMap, XMLTransfer xfer) {
        index_ = index;
        uri_ = uri;
        fileName_ = fileName;
        lastModified_ = lastModified;
        sink_ = sink;
        logger_ = logger;
        metaFuncMap_ = metaFuncMap;
        xfer_ = xfer;
    }

    public int run() {
        String systemID = null;
        try {
            systemID = uri_.toString();
            logger_.accept("Processing " + systemID, Logger.Level.VERBOSE);

            List<XPathExpression> referents = sink_.referents();
            List<String> referredContents;
            Source source;
            if (!referents.isEmpty()) {
                Document document = xfer_.parse(new StreamSource(systemID));
                referredContents = Referral.extract(document, referents);
                logger_.accept("Referred source data: "
                    + String.join(", ", referredContents), Logger.Level.DEBUG);

                if (!metaFuncMap_.isEmpty()) {
                    DocumentFragment metas = document.createDocumentFragment();
                    addMetaInformation(metaFuncMap_, uri_, (target, data) ->
                        metas.appendChild(
                            document.createProcessingInstruction(target, data)));
                    Element docElem = document.getDocumentElement();
                    docElem.insertBefore(metas, docElem.getFirstChild());
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

            // Do processing.
            Result result = sink_.startOne(index_, fileName_, lastModified_, referredContents);
            if (result != null) {
                try {
                    xfer_.transfer(source, result);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                } catch (BuildException | InterruptedException e) {
                    logger_.accept("Aborting processing " + systemID, Logger.Level.WARN);
                    if (e instanceof BuildException) {
                        if (!(e instanceof ChionographisBuildException) ||
                            !((ChionographisBuildException) e).isLoggedAlready()) {
                            logger_.accept("  Cause: " + e, Logger.Level.WARN);
                            e.printStackTrace();    // TODO: Use "log"
                        }
                    }
                    try {
                        sink_.abortOne(result);
                    } catch (FatalityException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        throw new FatalityException(ex);
                    }
                    return 0;
                }
                sink_.finishOne(result);
            }

            return 1;

        } catch (FatalityException e) {
            throw e;
        } catch (ChionographisBuildException e) {
            return 0;
        } catch (Exception e) {
            logger_.accept(e.toString(), Logger.Level.WARN);
            logger_.accept("Aborting processing " + systemID, Logger.Level.WARN);
            if (!(e instanceof ChionographisBuildException) ||
                !((ChionographisBuildException) e).isLoggedAlready()) {
                logger_.accept("  Cause: " + e, Logger.Level.WARN);
                e.printStackTrace();    // TODO: Use "log"
            }
            return 0;
        }
    }

    private void addMetaInformation(
            Map<String, Function<URI, String>> metaFuncMap,
            URI sourceURI, BiConsumer<String, String> consumer) {
        for (Map.Entry<String, Function<URI, String>> metaFunc : metaFuncMap.entrySet()) {
            String target = metaFunc.getKey();
            String data = metaFunc.getValue().apply(sourceURI);
            logger_.accept("Adding a processing instruction: target=" + target + ", data=" + data,
                Logger.Level.DEBUG);
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
