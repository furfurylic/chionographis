/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.IOException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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
import org.apache.tools.ant.Location;
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

    private ThreadLocal<Function<Location, XMLReader>> reader_;
    private ThreadLocal<Function<Location, DocumentBuilder>> builder_;
    private ThreadLocal<Function<Location, Transformer>> identity_;

    private static final XMLTransfer DEFAULT = new XMLTransfer();

    public static XMLTransfer getDefault() {
        return DEFAULT;
    }

    /**
     * Creates a new instance which uses the specified resolvers.
     *
     * @param resolver
     *      a SAX entity resolver.
     *      If {@code null} is specified,
     *      no special resolution of external entities are done.
     */
    public XMLTransfer(EntityResolver resolver) {
        reader_ = ThreadLocal.withInitial(
            () -> createXMLReaderCreator(resolver));
        builder_ = ThreadLocal.withInitial(
            () -> createDocumentBuilderCreator(resolver));
        identity_ = ThreadLocal.withInitial(
            () -> createIdentityTransformerCreator());
    }

    /**
     * Identical to {@code XMLTransfer(null)}.
     */
    public XMLTransfer() {
        this(null);
    }

    /**
     * Sends a document from a source to a result. Note that this method is destructive
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
     * @param location
     *      the location embedded into exceptions thrown, which can be {@code null}.
     *
     * @throws NonfatalBuildException
     *      if a recoverable error occurs.
     * @throws BuildException
     *      if a serious configuration problem occurs.
     */
    public void transfer(Source source, Result result, Location location) {
        try {
            if (source instanceof SAXSource) {
                SAXSource saxSource = (SAXSource) source;
                if (result instanceof SAXResult) {
                    transferSAX2SAX(saxSource, (SAXResult) result, location);
                    return;
                } else {
                    fillUpSAXSource(saxSource, location);
                    // Fall through
                }
            } else if (source instanceof DOMSource) {
                transfer((DOMSource) source, result, true, location);
                return;
            } else if (source instanceof StreamSource) {
                if (result instanceof SAXResult) {
                    InputSource input = SAXSource.sourceToInputSource(source);
                    SAXSource saxSource = new SAXSource(input);
                    if (saxSource.getSystemId() == null) {
                        saxSource.setSystemId(source.getSystemId());
                    }
                    transferSAX2SAX(saxSource, (SAXResult) result, location);
                    return;
                } else if (result instanceof DOMResult){
                    InputSource input = SAXSource.sourceToInputSource(source);
                    Document document = builder_.get().apply(location).parse(input);
                    transferDOM2DOM(new DOMSource(document, source.getSystemId()),
                        (DOMResult) result, true, location);
                    return;
                } else {
                    // Fall through
                }
            } else {
                // Fall through
            }

            identity_.get().apply(location).transform(source, result);
            copySystemID(source, result);

        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new BuildException(e, location);
        } catch (IOException | SAXException | TransformerException e) {
            throw new NonfatalBuildException(e, location);
        }
    }

    /**
     * Sends a document from a TrAX {@code DOMSource} to a {@code DOMResult}.
     * Note that this method is destructive to the source.
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
     * @param location
     *      the location embedded into exceptions thrown, which can be {@code null}.
     *
     * @throws NonfatalBuildException
     *      if a recoverable error occurs.
     * @throws BuildException
     *      if a serious configuration problem occurs.
     */
    public void transfer(DOMSource source, Result result, boolean adopts, Location location) {
        if (result instanceof DOMResult) {
            transferDOM2DOM(source, (DOMResult) result, adopts, location);
            return;
        }

        if (result instanceof StreamResult) {
            StreamResult streamResult = (StreamResult) result;
            DOMImplementationLS ls =
                (DOMImplementationLS) builder_.get().apply(location).getDOMImplementation();
            LSOutput output = ls.createLSOutput();
            output.setByteStream(streamResult.getOutputStream());
            output.setCharacterStream(streamResult.getWriter());
            output.setSystemId(streamResult.getSystemId());
            ls.createLSSerializer().write(source.getNode(), output);
        } else {
            try {
                identity_.get().apply(location).transform(source, result);
            } catch (TransformerException e) {
                throw new NonfatalBuildException(e, location);
            }
        }
        copySystemID(source, result);
    }

    /**
     * Reads an external document and parses it into a DOM document.
     *
     * @param source
     *      a TrAX {@code StreamSource} object to be read,which must not be {@code null}.
     * @param location
     *      the location embedded into exceptions thrown, which can be {@code null}.
     *
     * @return
     *      the resulted DOM document.
     *
     * @throws NonfatalBuildException
     *      if a recoverable error occurs.
     * @throws BuildException
     *      if a serious configuration problem occurs.
     */
    public Document parse(StreamSource source, Location location) {
        try {
            return builder_.get().apply(location).parse(SAXSource.sourceToInputSource(source));
        } catch (IOException | SAXException e) {
            throw new NonfatalBuildException(e, location);
        }
    }

    /**
     * Creates a new empty document.
     *
     * @param location
     *      the location embedded into exceptions thrown, which can be {@code null}.
     *
     * @return
     *      the resulted DOM document.
     *
     * @throws BuildException
     *      if a serious configuration problem occurs.
     */
    public Document newDocument(Location location) {
        return builder_.get().apply(location).newDocument();
    }

    private void fillUpSAXSource(SAXSource saxSource, Location location) {
        if (saxSource.getXMLReader() == null) {
            saxSource.setXMLReader(reader_.get().apply(location));
        } else if (saxSource.getXMLReader() instanceof XMLFilter) {
            XMLFilter filter = (XMLFilter) saxSource.getXMLReader();
            if (filter.getParent() == null) {
                filter.setParent(reader_.get().apply(location));
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

    private void transferSAX2SAX(SAXSource saxSource, SAXResult saxResult, Location location)
            throws SAXException, IOException {
        fillUpSAXSource(saxSource, location);
        setHandlers(saxSource.getXMLReader(), saxResult);
        saxSource.getXMLReader().parse(saxSource.getInputSource());
        copySystemID(saxSource, saxResult);
    }

    private void transferDOM2DOM(DOMSource source, DOMResult result, boolean adopts,
            Location location) {
        if (result.getNode() == null) {
            if (adopts) {
                result.setNode(source.getNode());
                source.setNode(null);
                copySystemID(source, result);
                return;
            } else {
                result.setNode(newDocument(location));
            }
        }

        Document resultDocument = (result.getNode().getNodeType() == Node.DOCUMENT_NODE) ?
            (Document) result.getNode() : result.getNode().getOwnerDocument();
        Consumer<Node> appendNode = (result.getNextSibling() != null) ?
            n -> result.getNextSibling().getParentNode().insertBefore(n, result.getNextSibling()) :
            n -> result.getNode().appendChild(n);

        Function<Node, Node> transferNode = adopts ?
            n -> (n.getNodeType() == Node.DOCUMENT_TYPE_NODE) ?
                    null : resultDocument.adoptNode(n) :
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
        copySystemID(source, result);
    }

    private static void copySystemID(Source source, Result result) {
        if (result.getSystemId() == null) {
            if (source.getSystemId() != null) {
                result.setSystemId(source.getSystemId());
            } else if (source instanceof SAXSource) {
                result.setSystemId(((SAXSource) source).getInputSource().getSystemId());
            }
        }
    }

    private static Function<Location, XMLReader> createXMLReaderCreator(EntityResolver resolver) {
        Function<Location, XMLReader> reader = new One<XMLReader, SAXParser>(
            l -> {
                SAXParserFactory pfac = SAXParserFactory.newInstance();
                pfac.setNamespaceAware(true);
                try {
                    pfac.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
                    return pfac.newSAXParser();
                } catch (ParserConfigurationException | SAXException e) {
                    throw new BuildException(e, l);
                }
            },
            (p, l) -> {
                try {
                    p.reset();
                    XMLReader xmlReader = p.getXMLReader();
                    if (resolver != null) {
                        xmlReader.setEntityResolver(resolver);
                    }
                    return xmlReader;
                } catch (SAXException e) {
                    throw new BuildException(e, l);
                }
            });
        return reader;
    }

    private static Function<Location, DocumentBuilder> createDocumentBuilderCreator(
            EntityResolver resolver) {
        Function<Location, DocumentBuilder> builder = new One<DocumentBuilder, DocumentBuilder>(
            l -> {
                try {
                    DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                    dbfac.setNamespaceAware(true);
                    return dbfac.newDocumentBuilder();
                } catch (ParserConfigurationException e) {
                    throw new BuildException(e, l);
                }
            },
            (b, l) -> {
                b.reset();
                if (resolver != null) {
                    b.setEntityResolver(resolver);
                }
                return b;
            });
        return builder;
    }

    private static Function<Location, Transformer> createIdentityTransformerCreator() {
        Function<Location, Transformer> transformer = new One<Transformer, Transformer>(
            l -> {
                try {
                    return TransformerFactory.newInstance().newTransformer();
                } catch (TransformerConfigurationException e) {
                    throw new BuildException(e, l);
                }
            },
            (t, l) -> {
                t.reset();
                t.setOutputProperty(OutputKeys.METHOD, "xml");
                return t;
            });
        return transformer;
    }

    private static final class One<T, U> implements Function<Location, T> {
        private Function<Location, U> factory_;
        private BiFunction<U, Location, T> resetter_;
        private U one_;

        public One(Function<Location, U> factory, BiFunction<U, Location, T> resetter) {
            factory_ = factory;
            resetter_ = resetter;
        }

        @Override
        public T apply(Location location) {
            if (one_ == null) {
                one_ = factory_.apply(location);
            }
            return resetter_.apply(one_, location);
        }
    }
}
