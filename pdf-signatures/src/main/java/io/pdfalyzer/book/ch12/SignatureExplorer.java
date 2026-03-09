package io.pdfalyzer.book.ch12;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.util.Store;

import lombok.Builder;
import lombok.Data;

/**
 * Chapter 12 — Explores every aspect of a PDF's signature architecture.
 * <p>
 * Demonstrates how to extract:
 * <ul>
 *   <li>Signature fields from the AcroForm field tree</li>
 *   <li>Signature dictionaries (/Type /Sig) and all their keys</li>
 *   <li>CMS SignedData from /Contents</li>
 *   <li>SignerInfo: signed attributes, digest algorithm, certificate</li>
 *   <li>SigFlags, seed values, lock dictionaries</li>
 * </ul>
 *
 * @see <a href="https://www.iso.org/standard/75839.html">ISO 32000-2:2020 §12.8</a>
 */
public class SignatureExplorer {

    @Data
    @Builder
    public static class ExplorationResult {
        private int totalFields;
        private int sigFlags;
        private boolean signaturesExist;
        private boolean appendOnly;
        private List<FieldInfo> fields;
    }

    @Data
    @Builder
    public static class FieldInfo {
        private String fieldName;
        private String fullyQualifiedName;
        private boolean signed;

        // Signature dictionary keys (ISO 32000-2 §12.8.1, Table 255)
        private String filter;
        private String subFilter;
        private int[] byteRange;
        private int contentsLength;
        private String signerName;
        private String reason;
        private String location;
        private String contactInfo;
        private String signingTime;

        // Transform method (ISO 32000-2 §12.8.2.2)
        private String transformMethod;
        private int docMdpPermissions;

        // CMS details (RFC 5652 §5)
        private String digestAlgorithmOid;
        private String signatureAlgorithmOid;
        private String certSubjectDN;
        private String certIssuerDN;
        private int certChainLength;
        private boolean hasSigningCertV2;

        // Seed value dictionary (ISO 32000-2 §12.7.5.5)
        private boolean hasSeedValue;
        private String seedFilter;
        private List<String> seedSubFilters;
        private List<String> seedDigestMethods;

        // Lock dictionary (ISO 32000-2 §12.7.5.5)
        private boolean hasLock;
        private String lockAction;
    }

    /**
     * Explores all signature fields and their dictionaries in the given PDF.
     *
     * @param pdfBytes raw PDF file bytes
     * @return detailed exploration result
     */
    public ExplorationResult explore(byte[] pdfBytes) throws IOException {
        List<FieldInfo> fields = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                return ExplorationResult.builder()
                        .totalFields(0)
                        .fields(fields)
                        .build();
            }

            // SigFlags (ISO 32000-2 §12.7.2, Table 225)
            int sigFlags = acroForm.getCOSObject().getInt(COSName.SIG_FLAGS, 0);

            for (PDField field : acroForm.getFieldTree()) {
                if (!(field instanceof PDSignatureField sigField)) continue;

                FieldInfo.FieldInfoBuilder info = FieldInfo.builder()
                        .fieldName(sigField.getPartialName())
                        .fullyQualifiedName(sigField.getFullyQualifiedName());

                PDSignature sig = sigField.getSignature();
                info.signed(sig != null);

                if (sig != null) {
                    extractSignatureDictKeys(sig, info);
                    extractTransformMethod(sigField, info);
                    extractCmsDetails(sig, pdfBytes, info);
                }

                extractSeedValue(sigField, info);
                extractLockDict(sigField, info);

                fields.add(info.build());
            }

            return ExplorationResult.builder()
                    .totalFields(fields.size())
                    .sigFlags(sigFlags)
                    .signaturesExist((sigFlags & 1) != 0)
                    .appendOnly((sigFlags & 2) != 0)
                    .fields(fields)
                    .build();
        }
    }

    /**
     * Extracts all keys from the signature dictionary (/Type /Sig).
     * Reference: ISO 32000-2 §12.8.1, Table 255.
     */
    private void extractSignatureDictKeys(PDSignature sig, FieldInfo.FieldInfoBuilder info) {
        info.filter(sig.getFilter());
        info.subFilter(sig.getSubFilter());
        info.byteRange(sig.getByteRange());
        info.signerName(sig.getName());
        info.reason(sig.getReason());
        info.location(sig.getLocation());
        info.contactInfo(sig.getContactInfo());

        if (sig.getSignDate() != null) {
            info.signingTime(sig.getSignDate().toInstant().toString());
        }

        // Contents length = actual CMS blob size (not the zero-padded hex string)
        COSDictionary sigDict = sig.getCOSObject();
        COSBase contentsBase = sigDict.getDictionaryObject(COSName.CONTENTS);
        if (contentsBase instanceof org.apache.pdfbox.cos.COSString contentsStr) {
            info.contentsLength(contentsStr.getBytes().length);
        }
    }

    /**
     * Extracts /Reference array → /TransformMethod (DocMDP, FieldMDP, UR).
     * Reference: ISO 32000-2 §12.8.2.2.
     */
    private void extractTransformMethod(PDSignatureField sigField, FieldInfo.FieldInfoBuilder info) {
        try {
            COSDictionary sigDict = sigField.getSignature().getCOSObject();
            COSBase refBase = sigDict.getDictionaryObject(COSName.getPDFName("Reference"));
            if (!(refBase instanceof COSArray refArray) || refArray.size() == 0) return;

            COSBase entry = refArray.getObject(0);
            if (!(entry instanceof COSDictionary refDict)) return;

            String method = refDict.getNameAsString(COSName.getPDFName("TransformMethod"));
            info.transformMethod(method);

            if ("DocMDP".equals(method)) {
                COSBase tpBase = refDict.getDictionaryObject(COSName.getPDFName("TransformParams"));
                if (tpBase instanceof COSDictionary tp) {
                    info.docMdpPermissions(tp.getInt(COSName.P, 2));
                }
            }
        } catch (Exception e) {
            // Field may not have a signature or reference array
        }
    }

    /**
     * Parses the CMS SignedData from /Contents and extracts signer info.
     * Reference: RFC 5652 §5 (SignedData), §5.3 (SignerInfo).
     */
    @SuppressWarnings("unchecked")
    private void extractCmsDetails(PDSignature sig, byte[] pdfBytes,
                                   FieldInfo.FieldInfoBuilder info) {
        try {
            byte[] contents = sig.getContents(pdfBytes);
            if (contents == null || contents.length == 0) return;

            CMSSignedData cmsData = new CMSSignedData(contents);
            Collection<SignerInformation> signers = cmsData.getSignerInfos().getSigners();
            if (signers.isEmpty()) return;

            SignerInformation signer = signers.iterator().next();
            info.digestAlgorithmOid(signer.getDigestAlgOID());
            info.signatureAlgorithmOid(signer.getEncryptionAlgOID());

            // Check for signing-certificate-v2 attribute (OID 1.2.840.113549.1.9.16.2.47)
            // This attribute is what makes it CAdES vs plain CMS
            info.hasSigningCertV2(
                    signer.getSignedAttributes() != null
                    && signer.getSignedAttributes().get(
                        new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.9.16.2.47")
                    ) != null
            );

            // Extract certificate chain
            Store<X509CertificateHolder> certStore = cmsData.getCertificates();
            info.certChainLength(certStore.getMatches(null).size());

            Collection<X509CertificateHolder> signerCerts = certStore.getMatches(signer.getSID());
            if (!signerCerts.isEmpty()) {
                X509Certificate cert = new JcaX509CertificateConverter()
                        .getCertificate(signerCerts.iterator().next());
                info.certSubjectDN(cert.getSubjectX500Principal().getName());
                info.certIssuerDN(cert.getIssuerX500Principal().getName());
            }
        } catch (Exception e) {
            // CMS parsing may fail for malformed signatures
        }
    }

    /**
     * Extracts the seed value dictionary (/SV).
     * Reference: ISO 32000-2 §12.7.5.5, Table 234.
     */
    private void extractSeedValue(PDSignatureField sigField, FieldInfo.FieldInfoBuilder info) {
        COSDictionary fieldDict = sigField.getCOSObject();
        COSBase svBase = fieldDict.getDictionaryObject(COSName.getPDFName("SV"));
        if (!(svBase instanceof COSDictionary sv)) return;

        info.hasSeedValue(true);
        info.seedFilter(sv.getNameAsString(COSName.FILTER));

        COSBase sfBase = sv.getDictionaryObject(COSName.getPDFName("SubFilter"));
        if (sfBase instanceof COSArray sfArray) {
            List<String> subFilters = new ArrayList<>();
            for (int i = 0; i < sfArray.size(); i++) {
                COSBase item = sfArray.getObject(i);
                if (item instanceof COSName name) subFilters.add(name.getName());
            }
            info.seedSubFilters(subFilters);
        }

        COSBase dmBase = sv.getDictionaryObject(COSName.getPDFName("DigestMethod"));
        if (dmBase instanceof COSArray dmArray) {
            List<String> digests = new ArrayList<>();
            for (int i = 0; i < dmArray.size(); i++) {
                COSBase item = dmArray.getObject(i);
                if (item instanceof COSName name) digests.add(name.getName());
            }
            info.seedDigestMethods(digests);
        }
    }

    /**
     * Extracts the lock dictionary (/Lock).
     * Reference: ISO 32000-2 §12.7.5.5.
     */
    private void extractLockDict(PDSignatureField sigField, FieldInfo.FieldInfoBuilder info) {
        COSDictionary fieldDict = sigField.getCOSObject();
        COSBase lockBase = fieldDict.getDictionaryObject(COSName.getPDFName("Lock"));
        if (!(lockBase instanceof COSDictionary lock)) return;

        info.hasLock(true);
        info.lockAction(lock.getNameAsString(COSName.getPDFName("Action")));
    }
}
