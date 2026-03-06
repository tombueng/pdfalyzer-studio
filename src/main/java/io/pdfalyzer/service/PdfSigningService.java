package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.PendingSignatureData;
import io.pdfalyzer.model.SigningKeyMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PdfSigningService {

    private static final int SIGNATURE_CONTAINER_SIZE = 32768;

    /**
     * Apply a digital signature to the given PDF bytes.
     * Returns the new (incrementally saved) PDF bytes.
     */
    public byte[] signDocument(byte[] pdfBytes, PendingSignatureData pending, SigningKeyMaterial keyMaterial) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                throw new IllegalStateException("PDF has no AcroForm — cannot sign");
            }

            PDSignatureField sigField = findSignatureField(acroForm, pending.getFieldName());
            if (sigField == null) {
                throw new IllegalStateException("Signature field not found: " + pending.getFieldName());
            }

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(COSName.getPDFName("ETSI.CAdES.detached"));
            signature.setSignDate(Calendar.getInstance());

            if (pending.getReason() != null && !pending.getReason().isBlank()) {
                signature.setReason(pending.getReason());
            }
            if (pending.getLocation() != null && !pending.getLocation().isBlank()) {
                signature.setLocation(pending.getLocation());
            }
            if (pending.getContactInfo() != null && !pending.getContactInfo().isBlank()) {
                signature.setContactInfo(pending.getContactInfo());
            }

            String cn = extractCN(keyMaterial.getCertificate());
            if (cn != null) {
                signature.setName(cn);
            }

            // Certification signature (DocMDP)
            if ("certification".equals(pending.getSignMode())) {
                applyCertificationTransform(doc, signature, sigField, pending.getDocMdpLevel());
            }

            sigField.setValue(signature);

            SignatureOptions sigOptions = new SignatureOptions();
            sigOptions.setPreferredSignatureSize(SIGNATURE_CONTAINER_SIZE);

            // Visual appearance
            if (!"invisible".equals(pending.getVisualMode())) {
                buildVisualAppearance(doc, sigField, pending);
            }

            // Embed biometric data in custom signature dictionary entry
            if (pending.getBiometricData() != null && !pending.getBiometricData().isBlank()) {
                embedBiometricData(signature, pending.getBiometricData(), pending.getBiometricFormat());
            }

            CmsSignatureInterface cmsInterface = new CmsSignatureInterface(keyMaterial, pending.getPadesProfile());
            doc.addSignature(signature, cmsInterface, sigOptions);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.saveIncremental(baos);
            log.info("Signed field '{}' in session (result {} bytes)", pending.getFieldName(), baos.size());
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Signing failed: " + e.getMessage(), e);
        }
    }

    // ── Field lookup ──────────────────────────────────────────────────────────

    private PDSignatureField findSignatureField(PDAcroForm acroForm, String fieldName) {
        for (PDField field : acroForm.getFieldTree()) {
            if (field instanceof PDSignatureField sf) {
                String fqn = sf.getFullyQualifiedName();
                String partial = sf.getPartialName();
                if (fieldName.equals(fqn) || fieldName.equals(partial)) {
                    return sf;
                }
            }
        }
        return null;
    }

    // ── Certification (DocMDP) ────────────────────────────────────────────────

    private void applyCertificationTransform(PDDocument doc, PDSignature signature,
                                              PDSignatureField sigField, int docMdpLevel) {
        int level = (docMdpLevel >= 1 && docMdpLevel <= 3) ? docMdpLevel : 2;

        COSDictionary sigRef = new COSDictionary();
        sigRef.setItem(COSName.TYPE, COSName.getPDFName("SigRef"));
        sigRef.setItem(COSName.getPDFName("TransformMethod"), COSName.getPDFName("DocMDP"));

        COSDictionary transformParams = new COSDictionary();
        transformParams.setItem(COSName.TYPE, COSName.getPDFName("TransformParams"));
        transformParams.setItem(COSName.getPDFName("P"), COSInteger.get(level));
        transformParams.setItem(COSName.getPDFName("V"), COSName.getPDFName("1.2"));
        sigRef.setItem(COSName.getPDFName("TransformParams"), transformParams);

        COSArray refArray = new COSArray();
        refArray.add(sigRef);
        signature.getCOSObject().setItem(COSName.getPDFName("Reference"), refArray);

        // Set /Perms /DocMDP in the document catalog
        COSDictionary perms = new COSDictionary();
        perms.setItem(COSName.getPDFName("DocMDP"), signature.getCOSObject());
        doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Perms"), perms);
    }

    // ── Visual appearance ─────────────────────────────────────────────────────

    private void buildVisualAppearance(PDDocument doc, PDSignatureField sigField,
                                        PendingSignatureData pending) throws IOException {
        PDRectangle rect = getWidgetRect(sigField);
        if (rect == null || rect.getWidth() < 1 || rect.getHeight() < 1) return;

        float w = rect.getWidth();
        float h = rect.getHeight();

        PDResources resources = new PDResources();
        byte[] streamContent;

        switch (pending.getVisualMode()) {
            case "text":
                streamContent = buildTextStream(doc, resources, w, h, pending.getDisplayName(), pending.getFontName());
                break;
            case "image":
                streamContent = buildImageStream(doc, resources, w, h, pending.getImageDataBase64());
                break;
            case "draw":
                streamContent = buildImageStream(doc, resources, w, h, pending.getDrawnImageBase64());
                break;
            default:
                return;
        }

        if (streamContent == null || streamContent.length == 0) return;

        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(new PDRectangle(w, h));
        form.setResources(resources);
        try (java.io.OutputStream os = form.getStream().createOutputStream()) {
            os.write(streamContent);
        }

        COSDictionary apDict = new COSDictionary();
        apDict.setItem(COSName.N, form);
        sigField.getWidgets().get(0).getCOSObject().setItem(COSName.AP, apDict);
    }

    private byte[] buildTextStream(PDDocument doc, PDResources resources, float w, float h,
                                    String displayName, String fontId) throws IOException {
        String text = displayName != null && !displayName.isBlank() ? displayName : "Digitally signed";
        float fontSize = Math.min(12, Math.min(w / (text.length() * 0.5f), h * 0.6f));
        fontSize = Math.max(6, fontSize);

        PDFont font = loadSigningFont(doc, fontId);
        COSName cosName = resources.add(font);

        // For embedded TrueType fonts, encode text as hex using the font's encoding
        byte[] encoded = font.encode(text);
        StringBuilder hex = new StringBuilder();
        for (byte b : encoded) {
            hex.append(String.format("%02X", b & 0xFF));
        }

        String ops = "BT\n"
                + "/" + cosName.getName() + " " + fontSize + " Tf\n"
                + "0 0 0 rg\n"
                + "2 " + (h - fontSize - 2) + " Td\n"
                + "<" + hex + "> Tj\n"
                + "ET\n";
        return ops.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private PDFont loadSigningFont(PDDocument doc, String fontId) throws IOException {
        if (fontId != null && !fontId.isBlank()) {
            String resourcePath = "fonts/signing/" + fontId + ".ttf";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    return org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, is);
                }
            }
            log.warn("Signing font not found: {}, falling back to Helvetica", fontId);
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private byte[] buildImageStream(PDDocument doc, PDResources resources,
                                     float w, float h, String base64Data) throws IOException {
        if (base64Data == null || base64Data.isBlank()) return null;

        String data = base64Data;
        if (data.contains(",")) {
            data = data.substring(data.indexOf(',') + 1);
        }

        byte[] imageBytes = Base64.getDecoder().decode(data);
        java.awt.image.BufferedImage bimg = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (bimg == null) return null;

        PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, imageBytes, "sig-visual");
        COSName imgName = resources.add(pdImage);

        float imgW = bimg.getWidth();
        float imgH = bimg.getHeight();
        float scale = Math.min(w / imgW, h / imgH);
        float drawW = imgW * scale;
        float drawH = imgH * scale;
        float xOff = (w - drawW) / 2;
        float yOff = (h - drawH) / 2;

        String ops = "q\n"
                + drawW + " 0 0 " + drawH + " " + xOff + " " + yOff + " cm\n"
                + "/" + imgName.getName() + " Do\n"
                + "Q\n";
        return ops.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private PDRectangle getWidgetRect(PDSignatureField sigField) {
        if (sigField.getWidgets().isEmpty()) return null;
        return sigField.getWidgets().get(0).getRectangle();
    }

    // ── Biometric data embedding ──────────────────────────────────────────────

    /**
     * Embeds biometric signature data (x, y, pressure, tilt, timing) into a custom
     * dictionary entry within the PDF signature dictionary. The data is stored under
     * the key /PDFalyzerBiometric as a sub-dictionary containing:
     *   /Format  - storage format name (json, json-zip, binary)
     *   /Data    - the encoded biometric payload
     *   /Version - schema version for future compatibility
     */
    private void embedBiometricData(PDSignature signature, String biometricJson, String format) {
        if (biometricJson == null || biometricJson.isBlank()) return;
        String fmt = (format != null) ? format : "json-zip";

        try {
            byte[] payload;
            switch (fmt) {
                case "json":
                    payload = biometricJson.getBytes(StandardCharsets.UTF_8);
                    break;
                case "binary":
                    payload = encodeBiometricBinary(biometricJson);
                    fmt = "binary";
                    break;
                default: // "json-zip"
                    payload = deflateBytes(biometricJson.getBytes(StandardCharsets.UTF_8));
                    fmt = "json-zip";
                    break;
            }

            COSDictionary bioDict = new COSDictionary();
            bioDict.setName(COSName.getPDFName("Format"), fmt);
            bioDict.setInt(COSName.getPDFName("Version"), 1);
            bioDict.setItem(COSName.getPDFName("Data"), new COSString(payload));
            bioDict.setInt(COSName.getPDFName("UncompressedSize"), biometricJson.getBytes(StandardCharsets.UTF_8).length);

            signature.getCOSObject().setItem(COSName.getPDFName("PDFalyzerBiometric"), bioDict);
            log.info("Embedded biometric data: format={}, payload={} bytes", fmt, payload.length);
        } catch (Exception e) {
            log.warn("Failed to embed biometric data: {}", e.getMessage());
        }
    }

    private byte[] deflateBytes(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.BEST_COMPRESSION))) {
            dos.write(input);
        }
        return baos.toByteArray();
    }

    /**
     * Compact binary encoding: each point is 14 bytes
     * (float32 x, float32 y, float32 pressure, uint16 tiltX+tiltY packed, float32 time).
     * Stroke boundaries encoded as a prefix array of point counts per stroke.
     */
    private byte[] encodeBiometricBinary(String json) {
        // Parse the JSON to extract strokes array
        // The biometric JSON has structure: { strokes: [[{x,y,p,t,tiltX,tiltY},...], ...], ... }
        // Use a simple parse approach since we don't want a JSON library dependency here
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var strokesNode = root.get("strokes");
            if (strokesNode == null || !strokesNode.isArray()) {
                return json.getBytes(StandardCharsets.UTF_8); // fallback
            }

            int totalPoints = 0;
            for (var stroke : strokesNode) {
                totalPoints += stroke.size();
            }

            // Header: 4 bytes magic + 4 bytes numStrokes + 4 bytes per stroke (point count)
            // Points: 20 bytes each (4*float32 + 2*int16)
            int headerSize = 8 + strokesNode.size() * 4;
            int pointSize = 20;
            ByteBuffer buf = ByteBuffer.allocate(headerSize + totalPoints * pointSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // Magic "BIO1"
            buf.put((byte) 'B').put((byte) 'I').put((byte) 'O').put((byte) '1');
            buf.putInt(strokesNode.size());

            // Stroke lengths
            for (var stroke : strokesNode) {
                buf.putInt(stroke.size());
            }

            // Points
            for (var stroke : strokesNode) {
                for (var pt : stroke) {
                    buf.putFloat((float) pt.get("x").asDouble());
                    buf.putFloat((float) pt.get("y").asDouble());
                    buf.putFloat((float) pt.get("p").asDouble(0.5));
                    buf.putShort((short) pt.get("tiltX").asInt(0));
                    buf.putShort((short) pt.get("tiltY").asInt(0));
                    buf.putFloat((float) pt.get("t").asDouble());
                }
            }

            byte[] result = new byte[buf.position()];
            buf.rewind();
            buf.get(result);
            return result;
        } catch (Exception e) {
            log.warn("Binary biometric encoding failed, falling back to deflated JSON: {}", e.getMessage());
            try {
                return deflateBytes(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                return json.getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    // ── CMS/PKCS#7 signing ───────────────────────────────────────────────────

    private static class CmsSignatureInterface implements SignatureInterface {

        private final SigningKeyMaterial keyMaterial;
        private final String padesProfile;

        CmsSignatureInterface(SigningKeyMaterial keyMaterial, String padesProfile) {
            this.keyMaterial = keyMaterial;
            this.padesProfile = padesProfile;
        }

        @Override
        public byte[] sign(InputStream content) throws IOException {
            try {
                String algo = keyMaterial.getPrivateKey().getAlgorithm();
                String sigAlgo = "EC".equals(algo) ? "SHA256withECDSA" : "SHA256withRSA";

                // Read content for signing
                byte[] contentBytes = content.readAllBytes();

                // Build certificate store
                List<X509Certificate> certList = new ArrayList<>(keyMaterial.getChain());
                JcaCertStore certStore = new JcaCertStore(certList);

                // Content signer
                ContentSigner signer = new JcaContentSignerBuilder(sigAlgo)
                        .setProvider("BC")
                        .build(keyMaterial.getPrivateKey());

                // Digest calculator
                DigestCalculatorProvider dcProvider = new JcaDigestCalculatorProviderBuilder()
                        .setProvider("BC").build();

                // Build signer info with ESS signing-certificate-v2 for PAdES-B-B
                JcaSignerInfoGeneratorBuilder signerBuilder = new JcaSignerInfoGeneratorBuilder(dcProvider);

                // Add ESS signing-certificate-v2 attribute
                AttributeTable signedAttrs = buildSignedAttributes(keyMaterial.getCertificate());
                signerBuilder.setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(signedAttrs));

                SignerInfoGenerator signerInfoGen = signerBuilder.build(signer, keyMaterial.getCertificate());

                CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
                gen.addSignerInfoGenerator(signerInfoGen);
                gen.addCertificates(certStore);

                CMSProcessableByteArray cmsContent = new CMSProcessableByteArray(contentBytes);
                CMSSignedData signedData = gen.generate(cmsContent, false);
                return signedData.getEncoded();
            } catch (Exception e) {
                throw new IOException("CMS signing failed: " + e.getMessage(), e);
            }
        }

        private AttributeTable buildSignedAttributes(X509Certificate cert) throws Exception {
            // ESS signing-certificate-v2 (RFC 5035) — required for PAdES-B-B
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] certHash = md.digest(cert.getEncoded());

            AlgorithmIdentifier hashAlgo = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
            ESSCertIDv2 essCertId = new ESSCertIDv2(hashAlgo, certHash);
            SigningCertificateV2 sigCertV2 = new SigningCertificateV2(new ESSCertIDv2[]{essCertId});

            ASN1EncodableVector v = new ASN1EncodableVector();
            v.add(new Attribute(
                    PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                    new DERSet(sigCertV2)));

            return new AttributeTable(v);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractCN(X509Certificate cert) {
        if (cert == null) return null;
        String dn = cert.getSubjectX500Principal().getName();
        var match = java.util.regex.Pattern.compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return match.find() ? match.group(1).trim() : null;
    }
}
