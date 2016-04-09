/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.IOException;
import java.util.function.Consumer;
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

/**
 * Objects of this class transfer documents from a source to another result.
 *
 *<p>All methods of this class are thread-safe.</p>
 */
final class XMLTransfer {

    private ThreadLocal<Supplier<XMLReader>> reader_;
    private ThreadLocal<Supplier<DocumentBuilder>> builder_;
    private ThreadLocal<Supplier<Transformer>> identity_;

    /**
     * Creates a new instance which uses the specified entity resolver.
     *
     * @param resolver
     *      a SAX entity resolver.
     *      If {@code null} is specified, no special resolution of external entities are done.
     */
    public XMLTransfer(EntityResolver resolver) {
        reader_ = ThreadLocal.withInitial(() -> createXMLReaderSupplier(resolver));
        builder_ = ThreadLocal.withInitial(() -> createDocumentBuilderSupplier(resolver));
        identity_ = ThreadLocal.withInitial(() -> createIdentityTransformerSupplier());
    }

    /**
     * Identical to {@code XMLTransfer(null)}.
     */
    public XMLTransfer() {
        this(null);
    }

    /**
     * Sends a document from a source to a result. Note that this method is detstuctive
     * to the source.
     *
     * <p>If the source is a {@code SAXSource} object and the {@code XMLReader} is absent,
     * this method complement a default reader. If the {@code SAXSource} object has a
     * {@code XMLFilter} and its parent is absent, similarly the parent is complemented.</p>
     *
     * <p>If the source is a {@code DOMSource} object and the result is a {@code DOMResult} object,
     * this method removes away the child nodes of the {@code DOMSource} object's node,
     * or removes away the {@code source}'s node itself (when {@code result} has no nodes).</p>
     *
     * @param source
     *      a TrAX {@code Source} object, which must not be {@code null}.
     * @param result
     *      a TrAX {@code Result} object, which must not be {@code null}.
     *
     * @throws FatalityException
     *      if a serious configuration problem occurs.
     * @throws BuildException
     *      if a recoverable error occurs.
     */
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
                transfer((DOMSource) source, result, true);
                return;
            } else if (source instanceof StreamSource) {
                if (result instanceof SAXResult) {
                    InputSource input = SAXSource.sourceToInputSource(source);
                    transferSAX2SAX(new SAXSource(input), (SAXResult) result);
                    return;
                } else if (result instanceof DOMResult){
                    InputSource input = SAXSource.sourceToInputSource(source);
                    Document document = builder_.get().get().parse(input);
                    transferDOM2DOM(new DOMSource(document), (DOMResult) result, true);
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

    /**
     * Sends a document from a TrAX {@code DOMSource} to a {@code DOMResult}.
     * Note that this method is detstuctive to the source.
     *
     * <p>If {@code adopts} is {@code true} and {@code result} is a {@code DOMResult} object,
     * this method removes away the child nodes of the {@code source}'s node,
     * or removes away the {@code source}'s node itself (when {@code result} has no nodes).
     * Otherwise, this method do copying and {@code source} is left unchanged.</p>
     *
     * @param source
     *      a TrAX {@code DOMSource} object, which must not be {@code null}.
     * @param result
     *      a TrAX {@code DOMResult} object, which must not be {@code null}.
     * @param adopts
     *      {@code true} if nodes are moved; {@code false} otherwise, that is, they are copied.
     *
     * @throws FatalityException
     *      if a serious configuration problem occurs.
     * @throws BuildException
     *      if a recoverable error occurs.
     */
    public void transfer(DOMSource source, Result result, boolean adopts) {
        if (result instanceof DOMResult) {
            transferDOM2DOM(source, (DOMResult) result, adopts);
            return;
        } else if (result instanceof StreamResult) {
            StreamResult streamResult = (StreamResult) result;
            DOMImplementationLS ls =
                (DOMImplementationLS) builder_.get().get().getDOMImplementation();
            LSOutput output = ls.createLSOutput();
            output.setByteStream(streamResult.getOutputStream());
            output.setCharacterStream(streamResult.getWriter());
            output.setSystemId(streamResult.getSystemId());
            ls.createLSSerializer().write(source.getNode(), output);
            return;
        } else {
            try {
                identity_.get().get().transform(source, result);
            } catch (TransformerException e) {
                throw new BuildException(e);
            }
        }
    }

    /**
     * Reads an external document and parses it into a DOM document.
     *
     * @param source
     *      a TrAX {@code StreamSource} object to be read,which must not be {@code null}.
     *
     * @return
     *      the resulted DOM document.
     *
     * @throws FatalityException
     *      if a serious configuration problem occurs.
     * @throws BuildException
     *      if a recoverable error occurs.
     */
    public Document parse(StreamSource source) {
        try {
            return builder_.get().get().parse(SAXSource.sourceToInputSource(source));
        } catch (IOException | SAXException e) {
            throw new BuildException(e);
        }
    }

    /**
     * Creates a new empty document.
     *
     * @return
     *      the resulted DOM document.
     *
     * @throws FatalityException
     *      if a serious configuration problem occurs.
     */
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

    private void transferDOM2DOM(DOMSource source, DOMResult result, boolean adopts) {
        if (result.getNode() == null) {
            if (adopts) {
                result.setNode(source.getNode());
                source.setNode(null);
                return;
            } else {
                result.setNode(newDocument());
            }
        }

        Document resultDocument = (result.getNode().getNodeType() == Node.DOCUMENT_NODE) ?
            (Document) result.getNode() : result.getNode().getOwnerDocument();
        Consumer<Node> appendNode = (result.getNextSibling() != null) ?
            n -> result.getNextSibling().getParentNode().insertBefore(n, result.getNextSibling()) :
            n -> result.getNode().appendChild(n);

        Function<Node, Node> transferNode = adopts ?
            n -> (n.getNodeType() == Node.DOCUMENT_TYPE_NODE) ?
                    null : resultDocument.importNode(n, true) :
            n -> (n.getNodeType() == Node.DOCUMENT_TYPE_NODE) ?
                    null : resultDocument.importNode(n, true);

        Node node = source.getNode().getFirstChild();
        while (node != null) {
            Node nextNode = node.getNextSibling();
            Node transferred = transferNode.apply(node);
            if (transferred != null) {
                appendNode.accept(transferred);
            }
            node = nextNode;
        }

        assert result.getNode() != null;
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
