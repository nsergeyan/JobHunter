package com.jobscout.scraper;

import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Reads <loc> URLs out of a sitemap.xml document. */
public final class SitemapReader {
    private SitemapReader() {
    }

    public static List<String> readLocs(String sitemapXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Sitemaps are trusted-source XML, but disable DOCTYPE processing anyway --
            // defends against XXE if a source ever gets compromised or spoofed.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(sitemapXml)));

            NodeList locNodes = doc.getElementsByTagName("loc");
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < locNodes.getLength(); i++) {
                urls.add(locNodes.item(i).getTextContent());
            }
            return urls;
        } catch (ParserConfigurationException | IOException | org.xml.sax.SAXException exc) {
            throw new ScraperException("Failed to parse sitemap XML", exc);
        }
    }
}
