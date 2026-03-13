package io.pdfalyzer.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.util.Store;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.CertificateChainEntry;
import io.pdfalyzer.model.PdfRevision;
import io.pdfalyzer.model.SignatureAnalysisResult;
import io.pdfalyzer.model.SignatureInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignatureAnalysisService {

    private final CertificateChainBuilder certificateChainBuilder;
    private final PdfRevisionParser pdfRevisionParser;

    public SignatureAnalysisResult analyzeSignatures(byte[] pdfBytes) throws IOException {
        List<SignatureInfo> signatures = new ArrayList<>();
        boolean hasCertification = false;

        // Parse revisions first (fast, local-only)
        List<PdfRevision> revisions = pdfRevisionParser.parseRevisions(pdfBytes);
        log.debug("Parsed {} PDF revisions", revisions.size());

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                return SignatureAnalysisResult.builder()
                        .signatures(signatures)
                        .revisions(revisions)
                        .build();
            }

            List<PDSignatureField> sigFields = new ArrayList<>();
            for (PDField field : acroForm.getFieldTree()) {
                if (field instanceof PDSignatureField sf) {
                    sigFields.add(sf);
                }
            }

            int eofCount = countEofMarkers(pdfBytes);

            for (PDSignatureField sigField : sigFields) {
                PDSignature sig = sigField.getSignature();
                SignatureInfo.SignatureInfoBuilder info = SignatureInfo.builder()
                        .fieldName(sigField.getPartialName())
                        .fullyQualifiedName(sigField.getFullyQualifiedName())
                        .signed(sig != null)
                        .signatureType(sig != null ? "approval" : "unsigned")
                        .pageIndex(-1)
                        .totalFileSize(pdfBytes.length);

                extractWidgetInfo(sigField, doc, info);
                extractLockInfo(sigField, info);

                if (sig != null) {
                    info.signerName(sig.getName());
                    info.subFilter(sig.getSubFilter());

                    Calendar signDate = sig.getSignDate();
                    if (signDate != null) {
                        info.signingTime(signDate.toInstant()
                                .atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }

                    analyzeByteRange(sig, pdfBytes.length, info);
                    parseCmsSignature(sig, pdfBytes, info);

                    // Map byte range to covered revisions
                    int[] byteRange = sig.getByteRange();
                    if (byteRange != null && byteRange.length >= 4) {
                        List<PdfRevision> coveredRevs = pdfRevisionParser.mapSignatureToRevisions(byteRange, revisions);
                        info.coveredRevisions(coveredRevs);

                        // Associate this signature field with its covered revisions
                        String fieldName = sigField.getPartialName();
                        for (PdfRevision rev : coveredRevs) {
                            if (!rev.getAssociatedSignatureFields().contains(fieldName)) {
                                rev.getAssociatedSignatureFields().add(fieldName);
                            }
                        }
                    }

                    if (detectCertificationSignature(sigField, doc)) {
                        info.signatureType("certification");
                        hasCertification = true;
                        info.docMdpPermissions(extractDocMdpPermissions(sigField, doc));
                    }

                    // Basic validation: check if cert is within validity period and byte range covers file
                    performBasicValidation(info);

                    detectModifications(sig, pdfBytes, eofCount, info);
                } else {
                    info.validationStatus("NOT_VALIDATED");
                }

                signatures.add(info.build());
            }
        }

        int signedCount = (int) signatures.stream().filter(SignatureInfo::isSigned).count();
        int validCount = (int) signatures.stream()
                .filter(s -> "VALID".equals(s.getValidationStatus())).count();
        int invalidCount = (int) signatures.stream()
                .filter(s -> "INVALID".equals(s.getValidationStatus())).count();
        int indeterminateCount = (int) signatures.stream()
                .filter(s -> "INDETERMINATE".equals(s.getValidationStatus())).count();

        return SignatureAnalysisResult.builder()
                .totalSignatureFields(signatures.size())
                .signedCount(signedCount)
                .unsignedCount(signatures.size() - signedCount)
                .validCount(validCount)
                .invalidCount(invalidCount)
                .indeterminateCount(indeterminateCount)
                .hasCertificationSignature(hasCertification)
                .signatures(signatures)
                .revisions(revisions)
                .build();
    }

    private void extractWidgetInfo(PDSignatureField sigField, PDDocument doc,
                                   SignatureInfo.SignatureInfoBuilder info) {
        try {
            List<PDAnnotationWidget> widgets = sigField.getWidgets();
            if (widgets == null || widgets.isEmpty()) return;
            PDAnnotationWidget widget = widgets.get(0);
            PDRectangle rect = widget.getRectangle();
            if (rect != null) {
                info.boundingBox(new double[]{
                        rect.getLowerLeftX(), rect.getLowerLeftY(),
                        rect.getWidth(), rect.getHeight()
                });
            }
            info.hasAppearance(widget.getNormalAppearanceStream() != null);

            PDPage widgetPage = widget.getPage();
            if (widgetPage != null) {
                List<PDPage> pages = new ArrayList<>();
                for (PDPage p : doc.getPages()) pages.add(p);
                int idx = pages.indexOf(widgetPage);
                if (idx >= 0) info.pageIndex(idx);
            }
        } catch (Exception e) {
            log.debug("Error extracting widget info for {}", sigField.getPartialName(), e);
        }
    }

    private void extractLockInfo(PDSignatureField sigField,
                                 SignatureInfo.SignatureInfoBuilder info) {
        try {
            COSDictionary fieldDict = sigField.getCOSObject();
            COSBase lockBase = fieldDict.getDictionaryObject(COSName.getPDFName("Lock"));
            if (lockBase instanceof COSDictionary lockDict) {
                String action = lockDict.getNameAsString(COSName.getPDFName("Action"));
                int perms = lockDict.getInt(COSName.P, 0);
                List<String> lockFields = new ArrayList<>();
                COSBase fieldsBase = lockDict.getDictionaryObject(COSName.getPDFName("Fields"));
                if (fieldsBase instanceof COSArray arr) {
                    for (int i = 0; i < arr.size(); i++) {
                        COSBase item = arr.getObject(i);
                        if (item instanceof COSString s) {
                            lockFields.add(s.getString());
                        }
                    }
                }
                info.lockInfo(SignatureInfo.LockInfo.builder()
                        .action(action)
                        .fields(lockFields)
                        .permissions(perms)
                        .build());
            }
        } catch (Exception e) {
            log.debug("Error extracting lock info", e);
        }
    }

    private void analyzeByteRange(PDSignature sig, long fileSize,
                                  SignatureInfo.SignatureInfoBuilder info) {
        int[] byteRange = sig.getByteRange();
        if (byteRange == null || byteRange.length < 4) return;

        List<SignatureInfo.ByteRangeSegment> segments = new ArrayList<>();
        segments.add(SignatureInfo.ByteRangeSegment.builder()
                .offset(byteRange[0]).length(byteRange[1]).label("Before signature").build());
        segments.add(SignatureInfo.ByteRangeSegment.builder()
                .offset(byteRange[2]).length(byteRange[3]).label("After signature").build());
        info.byteRange(segments);

        List<SignatureInfo.CoverageGap> gaps = new ArrayList<>();

        long gapStart = byteRange[0] + byteRange[1];
        long gapEnd = byteRange[2];
        if (gapEnd > gapStart) {
            gaps.add(SignatureInfo.CoverageGap.builder()
                    .offset(gapStart).length(gapEnd - gapStart)
                    .description("Signature value placeholder").build());
        }

        long coveredEnd = byteRange[2] + byteRange[3];
        if (coveredEnd < fileSize) {
            gaps.add(SignatureInfo.CoverageGap.builder()
                    .offset(coveredEnd).length(fileSize - coveredEnd)
                    .description("Uncovered trailing bytes (possible incremental update)").build());
        }

        info.coverageGaps(gaps);
        info.coversEntireFile(gaps.size() == 1 && "Signature value placeholder".equals(gaps.get(0).getDescription()));
    }

    @SuppressWarnings("unchecked")
    private void parseCmsSignature(PDSignature sig, byte[] pdfBytes,
                                   SignatureInfo.SignatureInfoBuilder info) {
        try {
            byte[] contents = sig.getContents(pdfBytes);
            if (contents == null || contents.length == 0) return;

            CMSSignedData cmsData = new CMSSignedData(contents);
            Collection<SignerInformation> signers = cmsData.getSignerInfos().getSigners();
            if (signers.isEmpty()) return;

            SignerInformation signer = signers.iterator().next();
            info.digestAlgorithm(signer.getDigestAlgOID());
            info.signatureAlgorithm(signer.getEncryptionAlgOID());

            Store<X509CertificateHolder> certStore = cmsData.getCertificates();
            Collection<X509CertificateHolder> certs = certStore.getMatches(signer.getSID());
            if (!certs.isEmpty()) {
                X509CertificateHolder certHolder = certs.iterator().next();
                X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);
                info.subjectDN(cert.getSubjectX500Principal().getName());
                info.issuerDN(cert.getIssuerX500Principal().getName());
                info.serialNumber(cert.getSerialNumber().toString(16));
                info.notBefore(cert.getNotBefore().toInstant()
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                info.notAfter(cert.getNotAfter().toInstant()
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                info.signatureAlgorithm(cert.getSigAlgName());
            }

            // Build full certificate chain from all certs in the CMS store
            List<CertificateChainEntry> chain = certificateChainBuilder.buildChainFromCms(cmsData, signer);
            info.certificateChain(chain);

        } catch (Exception e) {
            log.debug("Error parsing CMS signature: {}", e.getMessage());
        }
    }

    private boolean detectCertificationSignature(PDSignatureField sigField, PDDocument doc) {
        try {
            COSDictionary sigDict = sigField.getSignature().getCOSObject();
            COSBase refBase = sigDict.getDictionaryObject(COSName.getPDFName("Reference"));
            if (refBase instanceof COSArray refArray) {
                for (int i = 0; i < refArray.size(); i++) {
                    COSBase entry = refArray.getObject(i);
                    if (entry instanceof COSDictionary refDict) {
                        String method = refDict.getNameAsString(COSName.getPDFName("TransformMethod"));
                        if ("DocMDP".equals(method)) return true;
                    }
                }
            }
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            COSBase permsBase = catalog.getDictionaryObject(COSName.getPDFName("Perms"));
            if (permsBase instanceof COSDictionary permsDict) {
                return permsDict.containsKey(COSName.getPDFName("DocMDP"));
            }
        } catch (Exception e) {
            log.debug("Error detecting certification signature", e);
        }
        return false;
    }

    private int extractDocMdpPermissions(PDSignatureField sigField, PDDocument doc) {
        try {
            COSDictionary sigDict = sigField.getSignature().getCOSObject();
            COSBase refBase = sigDict.getDictionaryObject(COSName.getPDFName("Reference"));
            if (refBase instanceof COSArray refArray) {
                for (int i = 0; i < refArray.size(); i++) {
                    COSBase entry = refArray.getObject(i);
                    if (entry instanceof COSDictionary refDict) {
                        String method = refDict.getNameAsString(COSName.getPDFName("TransformMethod"));
                        if ("DocMDP".equals(method)) {
                            COSBase tpBase = refDict.getDictionaryObject(COSName.getPDFName("TransformParams"));
                            if (tpBase instanceof COSDictionary tp) {
                                return tp.getInt(COSName.P, 2);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting DocMDP permissions", e);
        }
        return 0;
    }

    private void performBasicValidation(SignatureInfo.SignatureInfoBuilder info) {
        // Build a temporary object to inspect fields
        SignatureInfo temp = info.build();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Check certificate validity period
        if (temp.getNotBefore() != null && temp.getNotAfter() != null) {
            try {
                java.time.Instant now = java.time.Instant.now();
                java.time.Instant notBefore = java.time.Instant.parse(temp.getNotBefore());
                java.time.Instant notAfter = java.time.Instant.parse(temp.getNotAfter());
                if (now.isBefore(notBefore)) {
                    errors.add("Certificate is not yet valid (starts " + temp.getNotBefore() + ")");
                } else if (now.isAfter(notAfter)) {
                    warnings.add("Certificate has expired (ended " + temp.getNotAfter() + ")");
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        // Check byte range coverage
        if (!temp.isCoversEntireFile() && temp.getCoverageGaps() != null) {
            for (SignatureInfo.CoverageGap gap : temp.getCoverageGaps()) {
                if (gap.getDescription() != null && gap.getDescription().contains("Uncovered")) {
                    warnings.add("Signature does not cover " + gap.getLength() + " trailing bytes");
                }
            }
        }

        // Determine overall status
        if (!errors.isEmpty()) {
            info.validationStatus("INVALID");
            info.validationMessage("Certificate validation failed");
        } else if (!warnings.isEmpty()) {
            info.validationStatus("INDETERMINATE");
            info.validationMessage("Signature has warnings — full validation requires trusted certificate chain");
        } else if (temp.getSubjectDN() != null) {
            info.validationStatus("INDETERMINATE");
            info.validationMessage("Structural integrity OK — full validation requires trusted certificate chain");
        } else {
            info.validationStatus("NOT_VALIDATED");
            info.validationMessage("Could not parse signature certificate");
        }

        info.validationWarnings(warnings);
        info.validationErrors(errors);
    }

    private void detectModifications(PDSignature sig, byte[] pdfBytes, int eofCount,
                                     SignatureInfo.SignatureInfoBuilder info) {
        List<SignatureInfo.ModificationWarning> warnings = new ArrayList<>();

        int[] byteRange = sig.getByteRange();
        if (byteRange != null && byteRange.length >= 4) {
            long coveredEnd = byteRange[2] + byteRange[3];
            long trailing = pdfBytes.length - coveredEnd;
            if (trailing > 0) {
                warnings.add(SignatureInfo.ModificationWarning.builder()
                        .severity("WARNING")
                        .description("Trailing bytes after signature")
                        .detail(trailing + " bytes after the signed byte range. " +
                                "This typically indicates an incremental update was appended after signing.")
                        .build());
            }
        }

        if (eofCount > 1) {
            warnings.add(SignatureInfo.ModificationWarning.builder()
                    .severity("WARNING")
                    .description("Multiple document revisions detected")
                    .detail(eofCount + " %%EOF markers found. Each revision after the signature " +
                            "may contain modifications that could invalidate the signature in some viewers.")
                    .build());
        }

        info.modifications(warnings);
    }

    private int countEofMarkers(byte[] pdfBytes) {
        int count = 0;
        byte[] pattern = "%%EOF".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i <= pdfBytes.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (pdfBytes[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) count++;
        }
        return count;
    }
}
