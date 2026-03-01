package io.pdfalyzer.service;

import io.pdfalyzer.model.CosUpdateRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class CosEditService {

    private static final Logger log = LoggerFactory.getLogger(CosEditService.class);

    public byte[] updateCosValue(byte[] pdfBytes, CosUpdateRequest request) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            COSBase target;
            String targetScope = request.getTargetScope();

            if ("docinfo".equalsIgnoreCase(targetScope)) {
                if (doc.getDocumentInformation() == null || doc.getDocumentInformation().getCOSObject() == null) {
                    throw new IllegalArgumentException("Document Info dictionary not available");
                }
                target = doc.getDocumentInformation().getCOSObject();
            } else {
                COSDocument cosDoc = doc.getDocument();
                COSObjectKey key = new COSObjectKey(request.getObjectNumber(),
                        request.getGenerationNumber());
                COSObject cosObj = cosDoc.getObjectFromPool(key);
                if (cosObj == null || cosObj.getObject() == null) {
                    throw new IllegalArgumentException("Object not found: " + key);
                }
                target = cosObj.getObject();
            }

            List<String> path = request.getKeyPath();
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Key path is required");
            }

            // Navigate to the parent container
            for (int i = 0; i < path.size() - 1; i++) {
                target = navigateInto(target, path.get(i));
            }

            String finalKey = path.get(path.size() - 1);
            String op = request.getOperation();
            if (op == null || op.equals("update")) {
                COSBase newCosValue = createCosValue(request.getNewValue(), request.getValueType());
                if (target instanceof COSDictionary) {
                    ((COSDictionary) target).setItem(COSName.getPDFName(finalKey), newCosValue);
                } else if (target instanceof COSArray) {
                    int idx = Integer.parseInt(finalKey);
                    ((COSArray) target).set(idx, newCosValue);
                } else {
                    throw new IllegalArgumentException("Cannot set value on " + target.getClass().getSimpleName());
                }
            } else if (op.equals("add")) {
                COSBase newCosValue = createCosValue(request.getNewValue(), request.getValueType());
                if (target instanceof COSDictionary) {
                    ((COSDictionary) target).setItem(COSName.getPDFName(finalKey), newCosValue);
                } else if (target instanceof COSArray) {
                    COSArray arr = (COSArray) target;
                    int idx;
                    try { idx = Integer.parseInt(finalKey); } catch (NumberFormatException e) { idx = -1; }
                    if (idx < 0 || idx > arr.size()) {
                        arr.add(newCosValue);
                    } else {
                        arr.add(idx, newCosValue);
                    }
                } else {
                    throw new IllegalArgumentException("Cannot add value to " + target.getClass().getSimpleName());
                }
            } else if (op.equals("remove")) {
                if (target instanceof COSDictionary) {
                    ((COSDictionary) target).removeItem(COSName.getPDFName(finalKey));
                } else if (target instanceof COSArray) {
                    int idx = Integer.parseInt(finalKey);
                    ((COSArray) target).remove(idx);
                } else {
                    throw new IllegalArgumentException("Cannot remove value from " + target.getClass().getSimpleName());
                }
            } else {
                throw new IllegalArgumentException("Unsupported operation: " + op);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
                log.info("Updated COS value: obj {} {} scope {} path {} = {}",
                    request.getObjectNumber(), request.getGenerationNumber(),
                    targetScope,
                    path, request.getNewValue());
            return out.toByteArray();
        }
    }

    private COSBase navigateInto(COSBase obj, String key) {
        if (obj instanceof COSObject) {
            obj = ((COSObject) obj).getObject();
        }
        if (obj instanceof COSDictionary) {
            COSBase result = ((COSDictionary) obj).getDictionaryObject(COSName.getPDFName(key));
            if (result == null) {
                throw new IllegalArgumentException("Key '" + key + "' not found in dictionary");
            }
            return result;
        } else if (obj instanceof COSArray) {
            int idx = Integer.parseInt(key);
            COSArray arr = (COSArray) obj;
            if (idx < 0 || idx >= arr.size()) {
                throw new IllegalArgumentException("Array index " + idx + " out of bounds (size: " + arr.size() + ")");
            }
            return arr.get(idx);
        }
        throw new IllegalArgumentException("Cannot navigate into " + obj.getClass().getSimpleName() + " with key '" + key + "'");
    }

    private COSBase createCosValue(String rawValue, String valueType) {
        if (valueType == null) {
            throw new IllegalArgumentException("Value type is required");
        }
        switch (valueType) {
            case "COSBoolean":
                return COSBoolean.getBoolean(Boolean.parseBoolean(rawValue));
            case "COSInteger":
                return COSInteger.get(Long.parseLong(rawValue));
            case "COSFloat":
                return new COSFloat(Float.parseFloat(rawValue));
            case "COSString":
                return new COSString(rawValue);
            case "COSName":
                return COSName.getPDFName(rawValue);
            case "COSDictionary":
                // Create new empty dictionary
                return new COSDictionary();
            case "COSArray":
                // Create new empty array
                return new COSArray();
            default:
                throw new IllegalArgumentException("Unsupported value type: " + valueType);
        }
    }
}