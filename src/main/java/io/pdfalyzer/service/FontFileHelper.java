package io.pdfalyzer.service;

import java.io.IOException;
import java.util.Locale;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import io.pdfalyzer.service.FontInspectorService.FontFileDownload;

/**
 * Handles font file extraction from PDF documents.
 * Used by {@link FontInspectorService}.
 */
@Component
public class FontFileHelper {

    FontFileDownload extractFontFileDownload(PDDocument doc, int objNum, int genNum) throws IOException {
        COSDocument cosDoc = doc.getDocument();
        COSObject cosObj = cosDoc.getObjectFromPool(new COSObjectKey(objNum, genNum));
        if (cosObj == null || !(cosObj.getObject() instanceof COSDictionary)) return null;
        COSDictionary fontDict = (COSDictionary) cosObj.getObject();
        COSDictionary descriptor = resolveFontDescriptor(fontDict);
        if (descriptor == null) return null;

        for (String key : new String[]{"FontFile3", "FontFile2", "FontFile"}) {
            COSBase fontFile = dereference(descriptor.getDictionaryObject(COSName.getPDFName(key)));
            if (fontFile instanceof COSStream) {
                try (java.io.InputStream is = ((COSStream) fontFile).createInputStream()) {
                    byte[] data = is.readAllBytes();
                    String extension = determineFontExtension(key, (COSStream) fontFile);
                    String filename = buildDownloadFilename(fontDict, descriptor, extension, objNum, genNum);
                    String contentType = determineFontContentType(extension);
                    return new FontFileDownload(data, filename, contentType);
                }
            }
        }
        return null;
    }

    private String buildDownloadFilename(COSDictionary fontDict, COSDictionary descriptor,
                                          String extension, int objNum, int genNum) {
        String family = sanitizeFilenamePart(descriptor.getString(COSName.FONT_FAMILY));
        String fontName = sanitizeFilenamePart(cleanSubsetPrefix(readFontName(fontDict, descriptor)));

        StringBuilder base = new StringBuilder();
        if (!family.isBlank()) base.append(family);
        if (!fontName.isBlank() && !fontName.equalsIgnoreCase(family)) {
            if (base.length() > 0) base.append('-');
            base.append(fontName);
        }
        if (base.length() == 0) base.append("font");
        return base + "-" + objNum + "-" + genNum + "." + extension;
    }

    private String readFontName(COSDictionary fontDict, COSDictionary descriptor) {
        String name = descriptor.getNameAsString(COSName.FONT_NAME);
        if (name != null && !name.isBlank()) return name;
        name = fontDict.getNameAsString(COSName.BASE_FONT);
        if (name != null && !name.isBlank()) return name;
        COSBase descendants = dereference(fontDict.getDictionaryObject(COSName.DESCENDANT_FONTS));
        if (descendants instanceof COSArray descendantArray && descendantArray.size() > 0) {
            COSBase descendant = dereference(descendantArray.get(0));
            if (descendant instanceof COSDictionary descendantDict) {
                String descendantName = descendantDict.getNameAsString(COSName.BASE_FONT);
                if (descendantName != null && !descendantName.isBlank()) return descendantName;
            }
        }
        return "font";
    }

    private String determineFontExtension(String fontFileKey, COSStream fontFileStream) {
        if ("FontFile2".equals(fontFileKey)) return "ttf";
        if ("FontFile".equals(fontFileKey)) return "pfb";
        String subtype = fontFileStream.getNameAsString(COSName.SUBTYPE);
        if (subtype == null || subtype.isBlank()) return "bin";
        return switch (subtype.trim().toLowerCase(Locale.ROOT)) {
            case "opentype" -> "otf";
            case "type1c", "cidfonttype0c" -> "cff";
            case "cidfonttype2", "truetype" -> "ttf";
            default -> "bin";
        };
    }

    private String determineFontContentType(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "pfb" -> "application/x-font-type1";
            case "cff" -> "application/font-sfnt";
            default -> "application/octet-stream";
        };
    }

    private String cleanSubsetPrefix(String fontName) {
        if (fontName == null) return "";
        int plus = fontName.indexOf('+');
        return plus == 6 ? fontName.substring(plus + 1) : fontName;
    }

    private String sanitizeFilenamePart(String value) {
        if (value == null) return "";
        String normalized = value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        return normalized.replaceAll("(^[-.]+|[-.]+$)", "");
    }

    private COSDictionary resolveFontDescriptor(COSDictionary fontDict) {
        COSBase directDescriptor = dereference(fontDict.getDictionaryObject(COSName.FONT_DESC));
        if (directDescriptor instanceof COSDictionary) return (COSDictionary) directDescriptor;

        COSBase descendants = dereference(fontDict.getDictionaryObject(COSName.DESCENDANT_FONTS));
        if (!(descendants instanceof COSArray)) return null;

        COSArray descendantArray = (COSArray) descendants;
        for (int i = 0; i < descendantArray.size(); i++) {
            COSBase descendant = dereference(descendantArray.get(i));
            if (!(descendant instanceof COSDictionary)) continue;
            COSBase desc = dereference(((COSDictionary) descendant).getDictionaryObject(COSName.FONT_DESC));
            if (desc instanceof COSDictionary) return (COSDictionary) desc;
        }
        return null;
    }

    private COSBase dereference(COSBase value) {
        if (value instanceof COSObject) return ((COSObject) value).getObject();
        return value;
    }
}
