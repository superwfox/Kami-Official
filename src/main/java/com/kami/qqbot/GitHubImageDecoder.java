package com.kami.qqbot;

import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.w3c.dom.Document;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Java-only SVG rasterization and ImageIO decoding, including the packaged WebP provider. */
final class GitHubImageDecoder {
    private static final String WIDTH = "kami.logicalWidth", HEIGHT = "kami.logicalHeight";

    static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    dimensions(width, height);
                    var parameters = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.max(width, height) / 1800);
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                    return withLogicalSize(reader.read(0, parameters), width, height);
                } finally { reader.dispose(); }
            }
        }
        return svg(bytes);
    }

    private static BufferedImage svg(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Document document = builder.parse(new ByteArrayInputStream(bytes));
            var root = document.getDocumentElement();
            if (!"svg".equals(root.getLocalName()) || !"http://www.w3.org/2000/svg".equals(root.getNamespaceURI()))
                throw new IOException("Not an SVG image");
            // Processing instructions can reference external stylesheets. SVG is rendered as inert image data.
            for (var node = document.getFirstChild(); node != null;) {
                var next = node.getNextSibling();
                if (node.getNodeType() == org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE) document.removeChild(node);
                node = next;
            }
            String[] box = root.getAttribute("viewBox").trim().split("[\\s,]+");
            double width = box.length == 4 ? positive(box[2], 300) : 300;
            double height = box.length == 4 ? positive(box[3], 150) : 150;
            width = positive(root.getAttribute("width"), width);
            height = positive(root.getAttribute("height"), height);
            int logicalWidth = (int) Math.ceil(width), logicalHeight = (int) Math.ceil(height);
            dimensions(logicalWidth, logicalHeight);
            double scale = Math.min(3, 2000 / Math.max(width, height));
            AtomicReference<BufferedImage> output = new AtomicReference<>();
            ImageTranscoder transcoder = new ImageTranscoder() {
                @Override public BufferedImage createImage(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }
                @Override public void writeImage(BufferedImage image, TranscoderOutput ignored) { output.set(image); }
            };
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) (width * scale));
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) (height * scale));
            transcoder.addTranscodingHint(ImageTranscoder.KEY_EXECUTE_ONLOAD, false);
            transcoder.addTranscodingHint(ImageTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
            transcoder.addTranscodingHint(ImageTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false);
            TransformerFactory transforms = TransformerFactory.newDefaultInstance();
            transforms.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transforms.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transforms.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            ByteArrayOutputStream safeXml = new ByteArrayOutputStream();
            transforms.newTransformer().transform(new DOMSource(document), new StreamResult(safeXml));
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(safeXml.toByteArray())), null);
            return withLogicalSize(output.get(), logicalWidth, logicalHeight);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("SVG image could not be decoded", e); }
    }

    private static double positive(String source, double fallback) {
        try {
            double value = Double.parseDouble(source.trim().replaceFirst("(?i)px$", ""));
            return Double.isFinite(value) && value > 0 ? value : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }
    private static void dimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > 40_000_000) throw new IOException("Image dimensions out of range");
    }
    private static BufferedImage withLogicalSize(BufferedImage image, int width, int height) {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(WIDTH, width); properties.put(HEIGHT, height);
        return new BufferedImage(image.getColorModel(), image.getRaster(), image.isAlphaPremultiplied(), properties);
    }
    static int width(BufferedImage image) { return logicalSize(image, WIDTH, image.getWidth()); }
    static int height(BufferedImage image) { return logicalSize(image, HEIGHT, image.getHeight()); }
    private static int logicalSize(BufferedImage image, String property, int fallback) {
        Object value = image.getProperty(property);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
}
