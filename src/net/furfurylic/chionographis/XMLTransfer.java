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
import org.w3c.dom.DocumentType;
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

    private static final ThreadLocal<BiFunction<EntityResolver, Location, XMLReader>>
        GET_READER = ThreadLocal.withInitial(() -> createXMLReaderGetter());
    private static final ThreadLocal<BiFunction<EntityResolver, Location, DocumentBuilder>>
        GET_BUILDER = ThreadLocal.withInitial(() -> createDocumentBuilderGetter());
    private static final ThreadLocal<BiFunction<Void, Location, Transformer>>
        GET_IDENTITY = ThreadLocal.withInitial(() -> createIdentityTransformerGetter());

    private EntityResolver resolver_  = null;

    /**
     * Creates a new instance which uses the specified resolver.
     *
     * @param resolver
     *      a SAX entity resolver, which can be {@code null}.
     */
    public XMLTransfer(EntityResolver resolver) {
        resolver_ = resolver;
    }

    /**
     * Identical to {@code XMLTransfer(null)}.
     */
    public XMLTransfer() {
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
                    saxSource.getXMLReader().setEntityResolver(resolver_);
                    // Fall through
                }
            } else if (source instanceof DOMSource) {
                transfer((DOMSource) source, result, true, location);
                return;
            } else if (source instanceof StreamSource) {
                if (result instanceof SAXResult) {
                    SAXSource saxSource = sourceToSAXSource(source);
                    transferSAX2SAX(saxSource, (SAXResult) result, location);
                    return;
                } else if (result instanceof DOMResult){
                    InputSource input = SAXSource.sourceToInputSource(source);
                    Document document =
                        getDocumentBuilder(location).parse(input);
                    transferDOM2DOM(new DOMSource(document, source.getSystemId()),
                        (DOMResult) result, true, location);
                    return;
                } else if (resolver_ != null) {
                    // Expansion of external entity references in StreamSource are not
                    // controllable with neither EntityResolver nor URIResolver.
                    // So here we use SAXSource instead.
                    SAXSource saxSource = sourceToSAXSource(source);
                    fillUpSAXSource(saxSource, location);
                    source = saxSource;
                    // Fall through
                }
            } else {
                // Fall through
            }

            getIdentityTransformer(location).transform(source, result);
            copySystemID(source, result);

        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new BuildException(e, location);
        } catch (IOException | SAXException | TransformerException e) {
            throw new NonfatalBuildException(e, location);
        }
    }

    private SAXSource sourceToSAXSource(Source source) {
        InputSource input = SAXSource.sourceToInputSource(source);
        SAXSource saxSource = new SAXSource(input);
        if (saxSource.getSystemId() == null) {
            saxSource.setSystemId(source.getSystemId());
        }
        return saxSource;
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
            DOMImplementationLS ls = (DOMImplementationLS)
                    getDocumentBuilder(location).getDOMImplementation();
            LSOutput output = ls.createLSOutput();
            output.setByteStream(streamResult.getOutputStream());
            output.setCharacterStream(streamResult.getWriter());
            output.setSystemId(streamResult.getSystemId());
            ls.createLSSerializer().write(source.getNode(), output);
        } else {
            try {
                Transformer id = getIdentityTransformer(location);
                DocumentType doctype = getOwnerDocument(source.getNode()).getDoctype();
                if (doctype != null) {
                    if (doctype.getPublicId() != null) {
                        id.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId());
                    }
                    if (doctype.getSystemId() != null) {
                        id.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId());
                    }
                }
                id.transform(source, result);
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
            return getDocumentBuilder(location)
                    .parse(SAXSource.sourceToInputSource(source));
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
        return getDocumentBuilder(location).newDocument();
    }

    private void fillUpSAXSource(SAXSource saxSource, Location location) {
        if (saxSource.getXMLReader() == null) {
            saxSource.setXMLReader(getXMLReader(location));
        } else if (saxSource.getXMLReader() instanceof XMLFilter) {
            XMLFilter filter = (XMLFilter) saxSource.getXMLReader();
            if (filter.getParent() == null) {
                filter.setParent(getXMLReader(location));
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

        Document resultDocument = getOwnerDocument(result.getNode());
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

        if (resultDocument.getDoctype() == null) {
            DocumentType doctype = getOwnerDocument(source.getNode()).getDoctype();
            if (doctype != null) {
                DocumentType resultDoctype =
                    resultDocument.getImplementation().createDocumentType(
                        resultDocument.getDocumentElement().getNodeName(),
                        doctype.getPublicId(), doctype.getSystemId());
                resultDocument.insertBefore(resultDoctype, resultDocument.getFirstChild());
            }
        }

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

    private static Document getOwnerDocument(Node n) {
        return (n.getNodeType() == Node.DOCUMENT_NODE) ? (Document) n : n.getOwnerDocument();
    }

    private XMLReader getXMLReader(Location location) {
        return GET_READER.get().apply(resolver_, location);
    }

    private DocumentBuilder getDocumentBuilder(Location location) {
        return GET_BUILDER.get().apply(resolver_, location);
    }

    private Transformer getIdentityTransformer(Location location) {
        return GET_IDENTITY.get().apply(null, location);
    }

    private static BiFunction<EntityResolver, Location, XMLReader> createXMLReaderGetter() {
        BiFunction<EntityResolver, Location, XMLReader> reader = new One<>(
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
            (r, p, l) -> {
                try {
                    p.reset();
                    XMLReader xmlReader = p.getXMLReader();
                    if (r != null) {
                        xmlReader.setEntityResolver(r);
                    }
                    return xmlReader;
                } catch (SAXException e) {
                    throw new BuildException(e, l);
                }
            });
        return reader;
    }

    private static BiFunction<EntityResolver, Location, DocumentBuilder>
            createDocumentBuilderGetter() {
        BiFunction<EntityResolver, Location, DocumentBuilder> builder = new One<>(
            l -> {
                try {
                    DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
                    dbfac.setNamespaceAware(true);
                    return dbfac.newDocumentBuilder();
                } catch (ParserConfigurationException e) {
                    throw new BuildException(e, l);
                }
            },
            (r, b, l) -> {
                b.reset();
                if (r != null) {
                    b.setEntityResolver(r);
                }
                return b;
            });
        return builder;
    }

    private static BiFunction<Void, Location, Transformer>
            createIdentityTransformerGetter() {
        BiFunction<Void, Location, Transformer> transformer = new One<>(
            l -> {
                try {
                    return TransformerFactory.newInstance().newTransformer();
                } catch (TransformerConfigurationException e) {
                    throw new BuildException(e, l);
                }
            },
            (v, t, l) -> {
                t.reset();
                t.setOutputProperty(OutputKeys.METHOD, "xml");
                return t;
            });
        return transformer;
    }

    private static final class One<S, O, T> implements BiFunction<S, Location, T> {
        private Function<Location, O> factory_;
        private Resetter<S, O, T> resetter_;
        private O one_;

        public One(Function<Location, O> factory, Resetter<S, O, T> resetter) {
            factory_ = factory;
            resetter_ = resetter;
        }

        @Override
        public T apply(S s, Location location) {
            if (one_ == null) {
                one_ = factory_.apply(location);
            }
            return resetter_.reset(s, one_, location);
        }

        @FunctionalInterface
        public static interface Resetter<S, O, T> {
            T reset(S s, O one, Location location);
        }
    }
}
