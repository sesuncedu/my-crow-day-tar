package com.criticollab.microdata;


import info.aduna.net.ParsedURI;
import org.apache.commons.io.input.ReaderInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.openrdf.model.*;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.XMLSchema;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RioSetting;
import org.openrdf.rio.helpers.RDFParserBase;
import org.openrdf.rio.helpers.RioSettingImpl;
import org.openrdf.rio.helpers.StatementCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

import static org.openrdf.model.datatypes.XMLDatatypeUtil.*;

public class RDFMicrodataParser extends RDFParserBase {
    private static final URI STANDARD_URI = ValueFactoryImpl.getInstance().createURI("http://www.w3.org/ns/formats/md");
    //******************* RIO RDF Parser Implementation
    public static final RDFFormat FORMAT = new RDFFormat("HTML5-Microdata2", Arrays.asList("text/html"),
            Charset.forName("UTF-8"), Arrays.asList("html"), STANDARD_URI, RDFFormat.NO_NAMESPACES,
            RDFFormat.NO_CONTEXTS);
    private static final String CHARSET_NAME = "UTF_16BE";
    private static final Charset CHARSET = Charset.forName(CHARSET_NAME);
    private static final String ITEMID = "itemid";
    private static final String[] SUBPROPERTY_REGISTRY_ATTRIBUTES = new String[]{"subPropertyOf", "equivalentProperty"};
    @SuppressWarnings("UnusedDeclaration")
    private static Logger logger = LoggerFactory.getLogger(RDFMicrodataParser.class);

    /**
     * The JSoup DOM from which to extract items
     */
    private Document document;
    private MicrodataRegistry registry;
    /**
     * A mapping from Item Elements to Resources. Part of the evaluation context defined
     * in the microdata-to-rdf spec)
     */
    private Map<Element, Resource> memory;
    /**
     * The URI of the current item being processed. Part of the evaluation context defined
     * in the microdata-to-rdf spec
     */
    /**
     * the prefixURI  for the current vocabulary, from the registry. Part of the evaluation context defined
     * in the microdata-to-rdf spec.
     */
    public static final RioSetting<Boolean> FAIL_ON_RELATIVE_ITEMIDS = new RioSettingImpl<>("com.criticollab.microdata.fail-on-relative-itemids",
            "Fail if relative ITEMID is encountered",
            Boolean.FALSE);
    public static final RioSetting<Boolean> FAIL_ON_RELATIVE_ITEMTYPES = new RioSettingImpl<>("com.criticollab.microdata.fail-on-relative-itemtypes",
            "Fail if relative ITEMTYPE is encountered",
            Boolean.FALSE);
    public static final RioSetting<URL> REGISTRY = new RioSettingImpl<>("com.criticollab.microdata.registry",
            "Registry to use",
            MicrodataRegistry.DEFAULT_REGISTRY_URL);


    /**
     * Gets the RDF format that this parser can parse.
     */
    @Override
    public RDFFormat getRDFFormat() {
        return FORMAT;
    }


    public RDFMicrodataParser() {
    }


    @Override
    public Collection<RioSetting<?>> getSupportedSettings() {
        Collection<RioSetting<?>> settings = super.getSupportedSettings();
        settings.add(FAIL_ON_RELATIVE_ITEMIDS);
        settings.add(FAIL_ON_RELATIVE_ITEMTYPES);
        return settings;
    }

    /**
     * Parses the data from the supplied InputStream, using the supplied baseURI
     * to resolve any relative URI references.
     *
     * @param in      The InputStream from which to read the data.
     * @param baseURI The URI associated with the data in the InputStream.
     * @throws java.io.IOException                 If an I/O error occurred while data was read from the InputStream.
     * @throws org.openrdf.rio.RDFParseException   If the parser has found an unrecoverable parse error.
     * @throws org.openrdf.rio.RDFHandlerException If the configured statement handler has encountered an
     *                                             unrecoverable error.
     */
    @Override
    public void parse(InputStream in, String baseURI) throws IOException, RDFParseException, RDFHandlerException {

        parse(in, null, baseURI);
    }

    /**
     * Parses the data from the supplied Reader, using the supplied baseURI to
     * resolve any relative URI references.  Since JSoup only takes bytes input streams,
     * we have to encode the contents so that jsoup can do its own decoding.
     *
     * @param reader  The Reader from which to read the data.
     * @param baseURI The URI associated with the data in the InputStream.
     * @throws java.io.IOException                 If an I/O error occurred while data was read from the InputStream.
     * @throws org.openrdf.rio.RDFParseException   If the parser has found an unrecoverable parse error.
     * @throws org.openrdf.rio.RDFHandlerException If the configured statement handler has encountered an
     *                                             unrecoverable error.
     */
    @Override
    public void parse(Reader reader, String baseURI) throws IOException, RDFParseException, RDFHandlerException {
        ReaderInputStream in = new ReaderInputStream(reader, CHARSET);
        parse(in, CHARSET_NAME, baseURI);
    }

    private void parse(InputStream in, String charsetName, String baseURI) throws IOException, RDFHandlerException, RDFParseException {
        setBaseURI(baseURI);
        try {
            document = Jsoup.parse(in, charsetName, baseURI);
            registry = new MicrodataRegistry(getParserConfig().get(REGISTRY));
            processDocument();
        } finally {
            clear();
        }

    }

    @Override
    protected void clear() {
        super.clear();
        document = null;
        memory = null;
        registry = null;
    }


    public Model extract(Document doc) throws RDFHandlerException, RDFParseException, IOException {
        document = doc;
        Model model;
        try {
            setBaseURI(doc.baseUri());
            registry = new MicrodataRegistry(getParserConfig().get(REGISTRY));
            model = new LinkedHashModel();
            setRDFHandler(new StatementCollector(model));
            processDocument();
            setRDFHandler(null);
            return model;
        } finally {
            clear();
        }
    }

    private void processDocument() throws RDFHandlerException, RDFParseException {
        memory = new IdentityHashMap<>();
        getRDFHandler().startRDF();
        for (Element element : findTopLevelItems(document)) {
            processItem(element, null, null);
        }
        getRDFHandler().endRDF();
    }


    Resource processItem(Element itemElement, String currentItemType, String currentVocabulary) throws RDFParseException, RDFHandlerException {
        logger.debug("processing top level item in {} ", itemElement.nodeName());
        /*
        1. If there is an entry for item in memory, then let subject be the subject of that entry.
        Otherwise, if item has a global identifier and that global identifier is an absolute URL,
         let subject be that global identifier.
        Otherwise, let subject be a new blank node.
         */
        Resource subject = memory.get(itemElement);
        if (subject == null) {
            if (itemElement.hasAttr(ITEMID)) {
                String uriString = itemElement.attr(ITEMID);
                try {
                    subject = resolveURI(uriString);
                } catch (RDFParseException e) {
                    if (getParserConfig().get(FAIL_ON_RELATIVE_ITEMIDS)) {
                        reportFatalError(e);
                    } else {
                        reportWarning(e.getMessage());
                    }
                }
            }
        }
        if (subject == null) {
            subject = createBNode();
        }
        /*
           2. Add a mapping from item to subject in memory
         */

        memory.put(itemElement, subject);

    /*
     3. For each type returned from element.itemType of the element defining the item.
            1. If type is an absolute URL, generate the following triple:
                subject subject predicate rdf:type object type (as a URI reference)
     4. Set type to the first value returned from element.itemType of the element defining the item.

     */
        String primaryMicrodataType = null;

        if (itemElement.hasAttr("itemtype"))

        {
            String[] itemtypes = itemElement.attr("itemtype").split(" ");
            for (String itemtype : itemtypes) {
                ParsedURI uri = new ParsedURI(itemtype);
                if (!uri.isAbsolute()) {
                    if (getParserConfig().get(FAIL_ON_RELATIVE_ITEMTYPES)) {
                        reportFatalError("encountered relative itemtype; " + itemtype);
                    }
                } else {
                    rdfHandler.handleStatement(createStatement(subject, RDF.TYPE, createURI(uri.toString())));
                    if (primaryMicrodataType == null) {
                        primaryMicrodataType = itemtype;
                    }
                }
            }
        }

        /*
            5. Otherwise, set type to current type from evaluation context if not empty.
         */
        if (primaryMicrodataType == null)

        {
            primaryMicrodataType = currentItemType;
        }
        /*
            6. If the registry contains a URI prefix that is a character for character match of type up to the
               length of the URI prefix, set vocab as that URI prefix.
        */
        MicrodataRegistry.RegistryEntry registryEntry = null;
        String vocab = null;
        if (primaryMicrodataType != null) {
            registryEntry = getRegistry().match(primaryMicrodataType);

            if (registryEntry != null) {
                vocab = registryEntry.getPrefixURI();
            } else if (primaryMicrodataType.length() > 0) {
       /* 7. Otherwise,if type is not empty, construct vocab by removing everything following the last
            SOLIDUS U +002F ("/") or NUMBER SIGN U +0023 ("#") from the path component of type.
        */
                int index = primaryMicrodataType.lastIndexOf('#');
                if (index != -1) {
                    vocab = primaryMicrodataType.substring(0, index + 1);
                } else {
                    index = primaryMicrodataType.lastIndexOf('/');
                    if (index != -1)
                        vocab = primaryMicrodataType.substring(0, index + 1);
                }
            }
        }
        currentVocabulary = vocab;
        currentItemType = primaryMicrodataType;
        List<Element> itemProperties = findItemProperties(itemElement);
        //For each element element that has one or more property names and is one of the properties of the item item run the following substep:
        for (Element itemProperty : itemProperties) {
            logger.trace("itemProperty: {}" + itemProperty);
            // For each name in the element's property names, run the following substeps:
            if (itemProperty.hasAttr("itemprop")) {
                for (String name : itemProperty.attr("itemprop").split(" ")) {
    //              Let context be a copy of evaluation context with current type set to type.
                    //SES: let's not.
    //                  Let predicate be the result of generate predicate URI using context and name.
                    String predicate = generatePredicateURI(name, currentItemType, currentVocabulary);
                    Value value;
                    if (itemProperty.hasAttr("itemscope")) {
                        // If value is an item, then generate the triples for value using context. Replace value by the subject returned from those steps.
                        value = processItem(itemProperty, currentItemType, currentVocabulary);
                    } else {
                        value = createValue(itemProperty);
                    }
    //                    Let value be the property value of element.
    //                    Generate the following triple:
                    rdfHandler.handleStatement(createStatement(subject, createURI(predicate), value));
    //            subject subject predicate predicate object value
    //            If an entry exists in the registry for name in the vocabulary associated with vocab having the key subPropertyOf or equivalentProperty,
    //            for each such value equiv, generate the following triple:
    //            subject subject predicate equiv object value
                    if (registryEntry != null) {
                        for (String attr : SUBPROPERTY_REGISTRY_ATTRIBUTES) {
                            List<String> equivs = registryEntry.getPropertyAttributeAsListOfStrings(name, attr);
                            for (String equiv : equivs) {
                                rdfHandler.handleStatement(createStatement(subject, createURI(equiv), value));

                            }
                        }
                    }

                }
            }

            if (itemProperty.hasAttr("itemprop-reverse")) {
                for (String name : itemProperty.attr("itemprop-reverse").split(" ")) {
                    Value value;
                    if (itemProperty.hasAttr("itemscope")) {
                        // If value is an item, then generate the triples for value using context. Replace value by the subject returned from those steps.
                        value = processItem(itemProperty, currentItemType, currentVocabulary);
                    } else {
                        value = createValue(itemProperty);
                    }

                    if (!(value instanceof Literal)) {
                        String predicate = generatePredicateURI(name, currentItemType, currentVocabulary);
                        rdfHandler.handleStatement(createStatement((Resource) value, createURI(predicate), subject));
                    }
                }

            }
        }


        return subject;
    }

    private Value createValue(Element element) throws RDFParseException {
//        If the element is a URL property element (a, area, audio, embed, iframe, img, link, object, source, track or video)
//        The value is a URI reference created from element.itemValue. (See relevant attribute descriptions in [HTML5]).

        switch (element.nodeName()) {
            case "a":
            case "area":
            case "link": {
                if (!element.hasAttr("href")) {
                    throw new RDFParseException("missing href in " + element);
                }
                return resolveURI(element.attr("href"));
            }
            case "audio":
            case "embed":
            case "iframe":
            case "img":
            case "source":
            case "track":
            case "video": {
                if (!element.hasAttr("src")) {
                    throw new RDFParseException("missing src in " + element);
                }
                return resolveURI(element.attr("src"));
            }
            case "object": {
                if (!element.hasAttr("data")) {
                    throw new RDFParseException("missing data in " + element);
                }
                return resolveURI(element.attr("data"));

            }
            //        If the element is a meter or data element.
//                The value is a literal made from element.itemValue.

            case "meter":
            case "data": {
                if (!element.hasAttr("value")) {
                    throw new RDFParseException("missing value in " + element);
                }
                String value = element.attr("value");
                if (isValidInteger(value)) {
//                  If the value is a valid integer having the lexical form of xsd:integer [XMLSCHEMA11-2]
//                  The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#integer.
                    return createLiteral(value, null, XMLSchema.INTEGER);
                } else if (isValidDouble(value)) {
//                  If the value is a valid float number having the lexical form of xsd:double [XMLSCHEMA11-2]
//                  The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#double.
                    return createLiteral(value, null, XMLSchema.DOUBLE);
                } else {
//                  The value is a simple literal.
                    return createLiteral(value, null, null);
                }
            }


//        If the element is a meta element with a @content attribute.
//        If the element has a non-empty language, the value is a language-tagged string created from the value of the
//              @content attribute with language information set from the language of the property element.
//              Otherwise, the value is a simple literal created from the value of the @content attribute.
            case "meta": {
                if (!element.hasAttr("content")) {
                    throw new RDFParseException("Missing content in " + element);
                }
                return createLiteral(element.attr("content"), getLang(element), null);
            }

//        If the element is a time element.
//                The value is a literal made from element.itemValue.
//        Otherwise
//        If the element has a non-empty language, the value is a language-tagged string created from the value with language information set from the language of the property element. Otherwise, the value is a simple literal created from the value.
//                NOTE
            case "time": {
                URI datatype = null;
                String value;
                if (element.hasAttr("datetime")) {
                    value = element.attr("datetime");
                } else {
                    value = getTextContent(element);
                }
                if (isValidDate(value)) {
//                If the value is a valid date string having the lexical form of xsd:date [XMLSCHEMA11-2].
//                The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#date.
                    datatype = XMLSchema.DATE;
                } else if (isValidTime(value)) {
//                If the value is a valid time string having the lexical form of xsd:time [XMLSCHEMA11-2].
//                The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#time.
                    datatype = XMLSchema.TIME;
                } else if (isValidDateTime(value)) {
//                If the value is a valid local date and time string or valid global date and time string having the lexical form of xsd:dateTime [XMLSCHEMA11-2].
//                The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#dateTime.

                    datatype = XMLSchema.DATETIME;
                } else if (isValidGYearMonth(value)) {
//                  If the value is a valid month string having the lexical form of xsd:gYearMonth [XMLSCHEMA11-2].
//                  The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#gYearMonth.

                    datatype = XMLSchema.GYEARMONTH;
                } else if (isValidGYear(value)) {
//                If the value is a valid non-negative integer having the lexical form of xsd:gYear [XMLSCHEMA11-2].
//                The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#gYear.

                    datatype = XMLSchema.GYEAR;
                } else if (isValidDuration(value)) {
//                If the value is a valid duration string having the lexical form of xsd:duration [XMLSCHEMA11-2].
//                The value is a typed literal composed of the value and http://www.w3.org/2001/XMLSchema#duration.
                    datatype = XMLSchema.DURATION;
                }
                String lang = datatype != null ? null : getLang(element);
                return createLiteral(value, lang, datatype);

            }
//        The HTML valid yearless date string is similar to xsd:gMonthDay, but the lexical forms differ, so it is not included in this conversion.
//
//                See The time element in [HTML5].
//
//        Otherwise
//        If the element has a non-empty language, the value is a language-tagged string created from the value with language information set from the language of the property element. Otherwise, the value is a simple literal created from the value.
//                See The lang and xml:lang attributes in [HTML5] for determining the language of a node.


            default:
                return createLiteral(getTextContent(element), getLang(element), null);
        }
    }

    private String getTextContent(Element element) {
        StringBuilder buf = new StringBuilder();
        apppendTextContent(buf, element);
        return buf.toString();
    }

    private void apppendTextContent(StringBuilder buf, Node node) {
        if (node instanceof Element) {
            Element element = (Element) node;
            for (Node child : element.childNodes()) {
                apppendTextContent(buf, child);
            }
        } else if (node instanceof TextNode) {
            TextNode textNode = (TextNode) node;
            buf.append(textNode.getWholeText());
        } else {
            logger.info("ignoring node of type {}", node.getClass());
        }
    }

    String generatePredicateURI(String name, String currentType, String currentVocabulary) {
//        If name is an absolute URL, return name as a URI reference.
        ParsedURI parsedURI = new ParsedURI(name);
        if (parsedURI.isAbsolute()) {
            return name;
        }

//        If current type from context is null, there can be no current vocabulary. Return the URI reference that is the document base with its fragment set to the canonicalized fragment value of name.

        if (currentType == null) {
            return document.baseUri() + ("#") + name;
        }
//        Set expandedURI to the URI reference constructed by appending the canonicalized fragment value of name to current vocabulary, separated by a U+0023 NUMBER SIGN character ("#") unless the current vocabulary ends with either a U+0023 NUMBER SIGN character ("#") or SOLIDUS U+002F ("/").
//                Return expandedURI.
        //TODO:FIXME
        if (currentVocabulary.endsWith("/") || currentVocabulary.endsWith("#")) {
            return currentVocabulary + name;
        } else {
            return currentVocabulary + "#" + name;
        }

    }

    List<Element> findItemProperties(Element root) {
//        Let results, memory, and pending be empty lists of elements.
//
        List<Element> results = new ArrayList<>();
        Set<Element> memory = new HashSet<>();
        Queue<Element> pending = new ArrayDeque<>();
        //        Add the element root to memory.
        memory.add(root);
//
//        Add the child elements of root, if any, to pending.
        pending.addAll(root.children());

//
//        If root has an itemref attribute, split the value of that itemref attribute on spaces.

        if (root.hasAttr("itemref")) {
            String ids[] = root.attr("itemref").split(" ");
            // For each resulting token ID, if there is an element in the home subtree of root with the ID ID,
            // then add the first such element to pending.
            for (String id : ids) {
                Elements foundIds = document.select(String.format("[id=%s]", id));
                if (foundIds.size() > 0) {
                    pending.add(foundIds.get(0));
                }
            }
        }


//                Loop: If pending is empty, jump to the step labeled end of loop.
        while (!pending.isEmpty()) {
//                Remove an element from pending and let current be that element.
            Element current = pending.remove();
//        If current is already in memory, there is a microdata error; return to the step labeled loop.
//        Add current to memory.

            if (!memory.add(current)) {
                continue;
            }
//
//
//        If current does not have an itemscope attribute, then: add all the child elements of current to pending.
            if (!current.hasAttr("itemscope")) {
                pending.addAll(current.children());
            }
//       If current has an itemprop attribute specified and has one or more property names, then add current to results.
//
            String itemprop = current.attr("itemprop");
            if (itemprop.length() > 0) {
                results.add(current);
            } else {
                String itempropReverse = current.attr("itemprop-reverse");
                if (itempropReverse.length() > 0) {
                    results.add(current);
                }
            }
        }

//        End of loop: Sort results in tree order.
//                Return results.
//
        return results;
    }

    List<Element> findTopLevelItems(Document document) {
        return document.select("[itemscope]:not([itemprop]:not([itemprop-reverse])");
    }

    String getLang(Element element) {
        if (element.hasAttr("lang")) {
            String lang = element.attr("lang");
            if (lang.length() == 0) {
                lang = null;
            }
            return lang;
        } else if (element.parent() != null) {
            return getLang((element.parent()));
        } else {
            return null;
        }
    }

    public Document getDocument() {
        return document;
    }


    public void setDocument(Document document) {
        this.document = document;
    }

    public MicrodataRegistry getRegistry() {
        return registry;
    }

    public void setRegistry(MicrodataRegistry registry) {
        this.registry = registry;
    }
}

