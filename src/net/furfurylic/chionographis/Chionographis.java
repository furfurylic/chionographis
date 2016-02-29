/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.LogLevel;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * An Ant task class that performs cascading transformation to XML documents.
 * As of now, only files can be original sources of the processing.
 * 
 * <p>An object of this class behaves as a <i>sink driver</i>.</p> 
 */
public final class Chionographis extends MatchingTask implements SinkDriver {

    private Path srcDir_;
    private Path baseDir_;
    private int prefixCount_;
    private Map<String, String> prefixMap_;
    private Sinks sinks_;

    /**
     * Sole constructor.
     */
    public Chionographis() {
    }

    /**
     * Prepares to accept <i>sinks</i> and other configuration elements.
     */
    @Override
    public void init() {
        sinks_ = new Sinks(new ChionographisLogger());
        prefixCount_ = 0;
        prefixMap_ = new TreeMap<>();
    }
    
    /**
     * Sets the base directory of this task. If this is an relative path,
     * it is resolved by the project's base directory.
     * 
     * <p>By default, the base directory is identical to the project's base directory.</p>
     * 
     * @param baseDir
     *      the base directory of this task. 
     */
    public void setBaseDir(String baseDir) {
        baseDir_ = Paths.get(baseDir);
    }

    /**
     * Sets the original source directory. If this is an relative path,
     * it is resolved by {@linkplain #setBaseDir(String) this task's base directory}.
     * 
     * <p>By default, the original source directory is identical to {@linkplain
     * Chionographis#setBaseDir(String) this task's base directory}.</p>
     *   
     * @param srcDir
     *      the original source directory.
     */
    public void setSrcDir(String srcDir) {
        srcDir_ = Paths.get(srcDir);
    }

    /**
     * Adds a prefix-namespace URI mapping entry to this task.
     *
     * <p>The mapping information is needed by <i>{@linkplain Snip}</i> sinks when the
     * XPaths of their criteria includes namespace prefixes. <i>{@linkplain All}</i> and
     * <i>{@linkplain Transform}</i> sinks also can refer this mapping information, however,
     * it is not mandatory (they can use {@code "{namespaceURI}localName"} notation).</p>
     * 
     * @return
     *      an empty prefix-namespace URI mapping entry.
     * 
     * @see Snip#setSelect(String)
     * @see All#setRoot(String)
     * @see Transform#createParam()
     * @see Param#setName(String)
     */
    public Namespace createNamespace() {
        ++prefixCount_;
        return new Namespace(this::receiveNamespace);
    }
    
    /**
     * Receives a possibly imperfect prefix-namespace mapping entry.
     *
     * <p>This method is invoked by {@link Namespace#setPrefix(String)}.</p>
     * 
     * <p>One prefix can be invoked twice at most, and then in the first call <i>namespaceURI</i>
     * must be {@code null}.</p>
     * 
     * @param prefix
     *      the namespace prefix, which shall not be {@code null}.
     * @param namespaceURI
     *      the namespace URI, which may be {@code null}.
     */
    private void receiveNamespace(String prefix, String namespaceURI) {
        if ((prefix.length() >= 3) && prefix.substring(0, 3).toLowerCase().equals("xml")) {
            sinks_.log(this, "Bad namespace prefix: " + prefix, LogLevel.ERR);
            throw new BuildException(); // TODO: message
        }
        if (prefixMap_.put(prefix, namespaceURI) != null) {
            sinks_.log(this, "Namespace prefix " + prefix + " mapped twice", LogLevel.ERR);
            throw new BuildException(); // TODO: message
        }
        if (namespaceURI != null) {
            sinks_.log(this,
                "Namespace prefix added: " + prefix + '=' + namespaceURI, LogLevel.VERBOSE);
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Transform createTransform() {
        return sinks_.createTransform();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public All createAll() {
        return sinks_.createAll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snip createSnip() {
        return sinks_.createSnip();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Output createOutput() {
        return sinks_.createOutput();
    }

    // TODO: Make this task configurable to log level escalation (e.g. VERBOSE -> INFO)
    // TODO: Make this task able to accept soures other than files
    
    /**
     * Performs cascading XML document transformation.
     */
    @Override
    public void execute() {
        // Arrange various directories.
        File projectBaseDir = getProject().getBaseDir();
        if (projectBaseDir == null) {
            projectBaseDir = new File("");
        }
        Path baseDirPathAbsolute = projectBaseDir.getAbsoluteFile().toPath();
        if (baseDir_ == null) {
            baseDir_ = baseDirPathAbsolute;
        } else {
            baseDir_ = baseDirPathAbsolute.resolve(baseDir_);
        }
        if (srcDir_ == null) {
            srcDir_ = baseDir_;
        } else {
            srcDir_ = baseDir_.resolve(srcDir_);
        }

        // Find files to process.
        String[] includedFiles;
        URI[] includedURIs;
        {
            DirectoryScanner scanner = getDirectoryScanner(srcDir_.toFile());
            scanner.scan();
            includedFiles = scanner.getIncludedFiles();
            if (includedFiles.length == 0) {
                sinks_.log(this, "No input sources found", LogLevel.INFO);
                return;
            }
            includedURIs = Arrays.stream(includedFiles)
                                 .map(srcDir_::resolve)
                                 .map(Path::toUri)
                                 .toArray(URI[]::new);
        }
        sinks_.log(this, includedURIs.length + " input sources found", LogLevel.INFO);

        // Set up namespace context.
        NamespaceContext namespaceContext = createNamespaceContext();

        try {
            // Set up XML engines.
            SAXParser parser;
            Transformer identity;
            {
                SAXParserFactory pfac = SAXParserFactory.newInstance();
                pfac.setNamespaceAware(true);
                pfac.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
                parser = pfac.newSAXParser();
                identity = TransformerFactory.newInstance().newTransformer();
            }
           
            sinks_.init(baseDir_.toFile(), namespaceContext);
            // TODO: pass caching EntityResolver/URIResolver
            // TODO: pass SAXTransformerFactory

            // Tell whether input sources are newer.
            boolean[] includes =
                sinks_.preexamineBundle(includedURIs, includedFiles, Collections.<URI>emptySet());
            if (IntStream.range(0, includes.length).noneMatch(i -> includes[i])) {
                sinks_.log(this, "No input sources processed", LogLevel.INFO);
                sinks_.log(this, "  Skipped input sources are", LogLevel.DEBUG);
                Arrays.stream(includedURIs)
                    .forEach(u -> sinks_.log(this, "    " + u.toString(), LogLevel.DEBUG));
                return;
            }

            int count = 0;
            sinks_.startBundle();
            for (int i = 0; i < includedFiles.length; ++i) {
                String includedFile = includedFiles[i];
                String systemID = srcDir_.resolve(includedFile).toUri().toString();
                if (!includes[i]) {
                    sinks_.log(this, "Skipping " + systemID, LogLevel.DEBUG);
                    continue;
                }
                ++count;
                try {
                    // Prepare input source.
                    sinks_.log(this, "Processing " + systemID, LogLevel.VERBOSE);
                    InputSource input = SAXSource.sourceToInputSource(new StreamSource(systemID));

                    // Set up reader, possibly configured to search output.
                    XMLReader reader = parser.getXMLReader();
                    Result result = sinks_.startOne(i, includedFile);
                    String[] output = new String[] { null };
                    boolean searchesPI = sinks_.needsOutput();
                    if (searchesPI) {
                        sinks_.log(this, "  PI search required", LogLevel.DEBUG);
                        reader = new OutputFinderFilter(reader, s -> output[0] = s);
                    } else {
                        sinks_.log(this, "  PI search not required", LogLevel.DEBUG);
                    }

                    // Do processing.
                    identity.transform(new SAXSource(reader, input), result);

                    if (searchesPI) {
                        sinks_.log(this, "  PI data is " + output[0], LogLevel.DEBUG);
                    }
                    sinks_.finishOne(output[0]);

                    parser.reset();
                    identity.reset();

                } catch (TransformerException e) {
                    e.printStackTrace();    // TODO: Other notification
                    sinks_.abortOne();
                }
            }
            if (count > 0) {
                sinks_.log(this,
                    "Finishing results of " + count +" input sources", LogLevel.VERBOSE);
            } else {
                sinks_.log(this, "No input sources processed", LogLevel.INFO);
            }
            sinks_.finishBundle();
        } catch (SAXException | ParserConfigurationException | TransformerException e) {
            throw new BuildException(e);
        }
    }

    private NamespaceContext createNamespaceContext() {
        int unmappedNameCount = prefixCount_ - prefixMap_.size();
        if (unmappedNameCount > 0) {
            sinks_.log(this,
                unmappedNameCount + " namespace prefixes left not fully configured", LogLevel.ERR);
            Optional<String> paramNames = prefixMap_.entrySet().stream()
                                            .filter(e -> e.getValue() == null)
                                            .map(e -> e.getKey())
                                            .reduce((r, s) -> r += ", " + s);
            if (paramNames.isPresent()) {
                sinks_.log(this, 
                    "  Namespace prefixes without names are: " + paramNames.get(), LogLevel.ERR);
            }
            throw new BuildException(); // TODO: message
        }
        prefixMap_.put(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI);
        prefixMap_.put(XMLConstants.XMLNS_ATTRIBUTE, XMLConstants.XMLNS_ATTRIBUTE_NS_URI);
        return new PrefixMap(prefixMap_);
    }

    private final class ChionographisLogger implements Logger {

        @Override
        public void log(Object issuer, String message, LogLevel level) {
            try (Formatter formatter = new Formatter()) {
                String className = issuer.getClass().getSimpleName();
                formatter.format("%13s(%08x): %s", 
                    className.substring(0, Math.min(13, className.length())),
                    System.identityHashCode(issuer),
                    message);
                Chionographis.this.log(formatter.toString(), level.getLevel());
                // TODO: Object -> line header caching 
            }
        }       
    }
    
    private static class OutputFinderFilter extends XMLFilterImpl {

        private Consumer<String> outputHandler_;
        private int counter_;

        public OutputFinderFilter(XMLReader parent, Consumer<String> outputHandler) {
            super(parent);
            outputHandler_ = outputHandler;
            counter_ = 0;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            ++counter_;
            super.startElement(uri, localName, qName, atts);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            if ((counter_ == 1) && target.equals("chionographis-output")) {
                outputHandler_.accept(data);
                ++counter_;
            } else {
                super.processingInstruction(target, data);
            }
        }       
    }
    
    private static final class PrefixMap implements NamespaceContext {
        
        Map<String, String> prefixMap_;
        
        public PrefixMap(Map<String, String> prefixMap) {
            prefixMap_ = prefixMap;
        }
        
        @Override
        public String getNamespaceURI(String prefix) {
            return prefixMap_.get(prefix);
        }

        @Override
        public String getPrefix(String namespaceURI) {
            if (namespaceURI == null) {
                throw new IllegalArgumentException("Namespace URI is null");
            }
            return prefixMap_.entrySet().stream()
                .filter(e -> e.getKey().equals(namespaceURI))
                .map(e -> e.getKey())
                .findAny()
                .orElse(XMLConstants.DEFAULT_NS_PREFIX);
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            if (namespaceURI == null) {
                throw new IllegalArgumentException("Namespace URI is null");
            }
            return prefixMap_.entrySet().stream()
                .filter(e -> e.getKey().equals(namespaceURI))
                .map(e -> e.getKey())
                .iterator();
        }
        
    }
}
