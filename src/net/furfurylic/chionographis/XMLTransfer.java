/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.tools.ant.BuildException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

final class XMLTransfer {

    private ThreadLocal<Supplier<XMLReader>> reader_;
    private ThreadLocal<Supplier<DocumentBuilder>> builder_;
    private ThreadLocal<Supplier<Transformer>> identity_;

    public XMLTransfer(EntityResolver resolver) {
        reader_ = ThreadLocal.withInitial(() -> createXMLReaderSupplier(resolver));
        builder_ = ThreadLocal.withInitial(() -> createDocumentBuilderSupplier(resolver));
        identity_ = ThreadLocal.withInitial(() -> createIdentityTransformerSupplier());
    }

    public void transfer(Source source, Result result) {
        try {
            if (source instanceof SAXSource) {
                SAXSource saxSource = (SAXSource) source;
                if (result instanceof SAXResult) {
                    transferSAX2SAX(saxSource, (SAXResult) result);
                    return;
                } else {
                    fillUpSAXSource(saxSource);
                    // Fall through
                }
            } else if (source instanceof DOMSource) {
                DOMSource domSource = (DOMSource) source;
                if (result instanceof DOMResult) {
                    transferDOM2DOM(domSource, (DOMResult) result);
                    return;
                } else if (result instanceof StreamResult) {
                    StreamResult streamResult = (StreamResult) result;
                    DOMImplementationLS ls =
                        (DOMImplementationLS) builder_.get().get().getDOMImplementation();
                    LSOutput output = ls.createLSOutput();
                    output.setByteStream(streamResult.getOutputStream());
                    output.setCharacterStream(streamResult.getWriter());
                    output.setSystemId(streamResult.getSystemId());
                    ls.createLSSerializer().write(domSource.getNode(), output);
                    return;
                } else {
                    // Fall through
                }
            } else if (source instanceof StreamSource) {
                if (result instanceof SAXResult) {
                    InputSource input = SAXSource.sourceToInputSource(source);
                    transferSAX2SAX(new SAXSource(input), (SAXResult) result);
                    return;
                } else if (result instanceof DOMResult){
                    InputSource input = SAXSource.sourceToInputSource(source);
                    Document document = builder_.get().get().parse(input);
                    transferDOM2DOM(new DOMSource(document), (DOMResult) result);
                    return;
                } else {
                    // Fall through
                }
            } else {
                // Fall through
            }

            identity_.get().get().transform(source, result);

        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new FatalityException(e);
        } catch (IOException | SAXException | TransformerException e) {
            throw new BuildException(e);
        }
    }

    public Document parse(StreamSource source) {
        try {
            return builder_.get().get().parse(SAXSource.sourceToInputSource(source));
        } catch (IOException | SAXException e) {
            throw new BuildException(e);
        }
    }

    public Document newDocument() {
        return builder_.get().get().newDocument();
    }

    private void fillUpSAXSource(SAXSource saxSource) {
        if (saxSource.getXMLReader() == null) {
            saxSource.setXMLReader(reader_.get().get());
        } else if (saxSource.getXMLReader() instanceof XMLFilter) {
            XMLFilter filter = (XMLFilter) saxSource.getXMLReader();
            if (filter.getParent() == null) {
                filter.setParent(reader_.get().get());
            }
        }
        InputSource input = saxSource.getInputSource();
        if (input == null) {
            saxSource.setInputSource(new InputSource());
        }
    }

    private static void setHandlers(XMLReader reader, SAXResult saxResult)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        reader.setContentHandler(saxResult.getHandler());
        LexicalHandler lex = null;
        if (saxResult.getLexicalHandler() != null) {
            lex = saxResult.getLexicalHandler();
        } else if (saxResult.getHandler() instanceof LexicalHandler) {
            lex = (LexicalHandler) saxResult.getHandler();
        }
        if (lex != null) {
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", lex);
        }
    }

    private void transferSAX2SAX(SAXSource saxSource, SAXResult saxResult) throws SAXException, IOException {
        fillUpSAXSource(saxSource);
        setHandlers(saxSource.getXMLReader(), saxResult);
        saxSource.getXMLReader().parse(saxSource.getInputSource());
    }

    private void transferDOM2DOM(DOMSource domSource, DOMResult domResult) {
        Node resultNode = domResult.getNode();
        Document resultDocument = (resultNode.getNodeType() == Node.DOCUMENT_NODE) ?
            (Document) resultNode : resultNode.getOwnerDocument();
        Node node;
        while ((node = domSource.getNode().getFirstChild()) != null) {
            if (node.getNodeType() == Node.DOCUMENT_TYPE_NODE) {
                domSource.getNode().removeChild(node);
            } else {
                resultNode.appendChild(resultDocument.adoptNode(node));
            }
        }
        // TODO: what if nextSibling set?
    }

    private static Supplier<XMLReader> createXMLReaderSupplier(EntityResolver resolver) {
        Supplier<XMLReader> reader = new One<XMLReader, SAXParser>(
            () -> {
                SAXParserFactory pfac = SAXParserFactory.newInstance();
                pfac.setNamespaceAware(true);
                try {
                    pfac.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
                    return pfac.newSAXParser();
                } catch (ParserConfigurationException | SAXException e) {
                    throw new FatalityException(e);
                }
            },
            p -> {
                try {
                    p.reset();
                    XMLReader xmlReader = p.getXMLReader();
                    if (resolver != null) {
                        xmlReader.setEntityResolver(resolver);
                    }
                    return xmlReader;
                } catch (SAXException e) {
                    throw new FatalityException(e);
                }
            });
        return reader;
    }

    private static Supplier<DocumentBuilder> createDocumentBuilderSupplier(EntityResolver resolver) {
        Supplier<DocumentBuilder> builder = new One<DocumentBuilder, DocumentBuilder>(
            () -> {
                try {
                    DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                    dbfac.setNamespaceAware(true);
                    return dbfac.newDocumentBuilder();
                } catch (ParserConfigurationException e) {
                    throw new FatalityException(e);
                }
            },
            b -> {
                b.reset();
                if (resolver != null) {
                    b.setEntityResolver(resolver);
                }
                return b;
            });
        return builder;
    }

    private static Supplier<Transformer> createIdentityTransformerSupplier() {
        Supplier<Transformer> transformer = new One<Transformer, Transformer>(
            () -> {
                try {
                    TransformerFactory tfac = TransformerFactory.newInstance();
                    return tfac.newTransformer();
                } catch (TransformerConfigurationException e) {
                    throw new FatalityException(e);
                }
            },
            t -> {
                t.reset();
                t.setOutputProperty(OutputKeys.METHOD, "xml");
                return t;
            });
        return transformer;
    }

    private static final class One<T, U> implements Supplier<T> {
        private Supplier<U> factory_;
        private Function<U, T> resetter_;
        private U one_;

        public One(Supplier<U> factory, Function<U, T> resetter) {
            factory_ = factory;
            resetter_ = resetter;
        }

        @Override
        public T get() {
            if (one_ == null) {
                one_ = factory_.get();
            }
            return resetter_.apply(one_);
        }
    }
}
