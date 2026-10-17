package ch.software_atelier.java_lab.data;

import org.w3c.dom.Document;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import java.io.*;

/** Common, deterministic XML parser for editor validation and SVG metadata. */
public final class XmlDocuments {
    private XmlDocuments() {}
    public static Document parse(String source) throws IOException, SAXException {
        return parse(new InputSource(new StringReader(source)));
    }
    public static Document parse(byte[] source) throws IOException, SAXException {
        return parse(new InputSource(new ByteArrayInputStream(source)));
    }
    private static Document parse(InputSource source) throws IOException, SAXException {
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new Errors());return builder.parse(source);
        } catch(ParserConfigurationException e){throw new IOException("XML parser is unavailable",e);}
    }
    private static final class Errors extends DefaultHandler {
        @Override public void error(SAXParseException e) throws SAXException {throw e;}
        @Override public void fatalError(SAXParseException e) throws SAXException {throw e;}
    }
}
