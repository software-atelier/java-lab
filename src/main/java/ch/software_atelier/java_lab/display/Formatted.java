package ch.software_atelier.java_lab.display;

import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Explicit, escaped syntax-highlighted code/text output. */
public final class Formatted {
    private static final Set<String> FORMATS = Set.of("json", "yaml", "xml", "html", "css", "javascript", "java", "sql", "markdown", "csv", "text");
    private Formatted() {}

    public static void show(String format, Object content) {
        String language = normalize(format);
        String source = String.valueOf(content);
        if (source.length() > 1_000_000) throw new IllegalArgumentException("Formatted output exceeds 1,000,000 characters");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", language);
        payload.put("content", source);
        byte[] bytes = JsonOptions.encode(payload).getBytes(StandardCharsets.UTF_8);
        Display.emit("application/vnd.java-lab.code+json", language.toUpperCase(Locale.ROOT), 900, 320, bytes);
    }

    public static void json(Object content) { show("json", content); }
    public static void yaml(Object content) { show("yaml", content); }
    public static void xml(Object content) { show("xml", content); }
    public static void code(String format, Object content) { show(format, content); }

    /** Pretty-print validated JSON; malformed input fails instead of displaying misleading JSON. */
    public static void prettyJson(String content) {
        checkInput(content);
        JSONTokener parser = new JSONTokener(content);
        Object parsed = parser.nextValue();
        if (parser.nextClean() != 0) throw new IllegalArgumentException("JSON output contains trailing content");
        if (parsed instanceof JSONObject object) json(object.toString(2));
        else if (parsed instanceof JSONArray array) json(array.toString(2));
        else throw new IllegalArgumentException("JSON output must be an object or array");
    }

    /** Parse YAML with safe types, then emit an indented block representation. */
    public static void prettyYaml(String content) {
        checkInput(content);
        try {
            LoaderOptions loader = new LoaderOptions();
            loader.setAllowDuplicateKeys(false);
            loader.setMaxAliasesForCollections(50);
            loader.setCodePointLimit(1_000_000);
            DumperOptions dumper = new DumperOptions();
            dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            dumper.setIndent(2);
            dumper.setPrettyFlow(true);
            dumper.setAllowUnicode(true);
            dumper.setSplitLines(false);
            Yaml yaml = new Yaml(new SafeConstructor(loader), new org.yaml.snakeyaml.representer.Representer(dumper), dumper, loader);
            var documents = new java.util.ArrayList<Object>();
            yaml.loadAll(content).forEach(documents::add);
            String result = documents.size() == 1 ? yaml.dump(documents.get(0)) : yaml.dumpAll(documents.iterator());
            yaml(result);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage(), e);
        }
    }

    /** Pretty-print XML without DTDs, external entities, XInclude, or external stylesheets. */
    public static void prettyXml(String content) {
        checkInput(content);
        try {
            DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
            parser.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.setXIncludeAware(false);
            parser.setExpandEntityReferences(false);
            parser.setNamespaceAware(true);
            var builder = parser.newDocumentBuilder();
            builder.setErrorHandler(new StrictErrorHandler());
            var document = builder.parse(new InputSource(new StringReader(content)));
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, content.stripLeading().startsWith("<?xml") ? "no" : "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            xml(output.toString());
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid or unsafe XML: " + e.getMessage(), e);
        }
    }

    private static void checkInput(String content) {
        if (content == null || content.length() > 1_000_000) throw new IllegalArgumentException("Input is missing or exceeds 1,000,000 characters");
    }

    private static final class StrictErrorHandler extends DefaultHandler {
        @Override public void warning(SAXParseException e) throws SAXException { throw e; }
        @Override public void error(SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
    }

    private static String normalize(String format) {
        if (format == null) throw new IllegalArgumentException("Format is required");
        String value = format.strip().toLowerCase(Locale.ROOT);
        if (value.equals("yml")) value = "yaml";
        if (value.equals("js")) value = "javascript";
        if (value.equals("md")) value = "markdown";
        if (!FORMATS.contains(value)) throw new IllegalArgumentException("Unsupported output format: " + format);
        return value;
    }
}
