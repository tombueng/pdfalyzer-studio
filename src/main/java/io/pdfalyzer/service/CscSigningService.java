package io.pdfalyzer.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.CscCredential;
import io.pdfalyzer.model.CscProvider;
import io.pdfalyzer.model.CscSignResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Integrates CSC remote signing into the PDF signing pipeline.
 * The flow:
 * 1. Build CMS SignedData structure with signed attributes (ESS-signing-cert-v2)
 * 2. Compute hash of the signed attributes (the data-to-be-signed)
 * 3. Send hash to CSC provider via signHash endpoint
 * 4. Inject the raw signature value into the CMS structure
 * 5. Embed into the PDF via PDFBox incremental save
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CscSigningService {

    private static final int SIGNATURE_CONTAINER_SIZE = 32768;

    private final CscApiClient cscApiClient;

    /**
     * Sign a PDF field using a remote CSC provider.
     *
     * @param pdfBytes      the PDF document bytes
     * @param fieldName     the signature field name
     * @param provider      the CSC provider configuration
     * @param accessToken   OAuth2 access token for the provider
     * @param credentialId  the CSC credential ID to use
     * @param sad           the Signature Activation Data token
     * @param signAlgoOid   signing algorithm OID (null for provider default)
     * @param certChainB64  Base64-encoded certificate chain from CSC
     * @param reason        signing reason (optional)
     * @param location      signing location (optional)
     * @return signed PDF bytes
     */
    public byte[] signWithCsc(byte[] pdfBytes, String fieldName,
                               CscProvider provider, String accessToken,
                               String credentialId, String sad, String signAlgoOid,
                               List<String> certChainB64,
                               String reason, String location) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                throw new IllegalStateException("PDF has no AcroForm");
            }

            PDSignatureField sigField = findSignatureField(acroForm, fieldName);
            if (sigField == null) {
                throw new IllegalStateException("Signature field not found: " + fieldName);
            }

            // Parse certificate chain
            List<X509Certificate> certChain = parseCertChain(certChainB64);
            if (certChain.isEmpty()) {
                throw new IllegalStateException("No valid certificates from CSC provider");
            }
            X509Certificate signerCert = certChain.get(0);

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(org.apache.pdfbox.cos.COSName.getPDFName("ETSI.CAdES.detached"));
            signature.setSignDate(Calendar.getInstance());

            String cn = extractCN(signerCert);
            if (cn != null) signature.setName(cn);
            if (reason != null && !reason.isBlank()) signature.setReason(reason);
            if (location != null && !location.isBlank()) signature.setLocation(location);

            sigField.setValue(signature);

            SignatureOptions sigOptions = new SignatureOptions();
            sigOptions.setPreferredSignatureSize(SIGNATURE_CONTAINER_SIZE);

            // Create a CMS signer that delegates the actual signing to the CSC provider
            CscRemoteSignatureInterface cmsInterface = new CscRemoteSignatureInterface(
                    cscApiClient, provider, accessToken, credentialId, sad,
                    signAlgoOid, signerCert, certChain);

            doc.addSignature(signature, cmsInterface, sigOptions);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.saveIncremental(baos);
            log.info("CSC-signed field '{}' via {} ({} bytes)", fieldName, provider.getName(), baos.size());
            return baos.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException("CSC signing failed: " + e.getMessage(), e);
        }
    }

    // ── CMS with remote signing ──────────────────────────────────────────

    private static class CscRemoteSignatureInterface implements SignatureInterface {

        private final CscApiClient cscClient;
        private final CscProvider provider;
        private final String accessToken;
        private final String credentialId;
        private final String sad;
        private final String signAlgoOid;
        private final X509Certificate signerCert;
        private final List<X509Certificate> certChain;

        CscRemoteSignatureInterface(CscApiClient cscClient, CscProvider provider,
                                     String accessToken, String credentialId, String sad,
                                     String signAlgoOid, X509Certificate signerCert,
                                     List<X509Certificate> certChain) {
            this.cscClient = cscClient;
            this.provider = provider;
            this.accessToken = accessToken;
            this.credentialId = credentialId;
            this.sad = sad;
            this.signAlgoOid = signAlgoOid;
            this.signerCert = signerCert;
            this.certChain = certChain;
        }

        @Override
        public byte[] sign(InputStream content) throws IOException {
            try {
                // 1. Read the content (byte range data)
                byte[] contentBytes = content.readAllBytes();

                // 2. Build signed attributes with ESS-signing-certificate-v2
                AttributeTable signedAttrs = buildSignedAttributes(signerCert);

                // 3. Build the CMS structure without the actual signature
                //    We need to compute the hash of the signed attributes
                //    The signed attributes include the content-type and message-digest
                //    of the original content
                MessageDigest contentDigest = MessageDigest.getInstance("SHA-256");
                byte[] contentHash = contentDigest.digest(contentBytes);

                // 4. Build the signed attributes with the message digest
                ASN1EncodableVector signedAttrVec = new ASN1EncodableVector();

                // Content-Type attribute
                signedAttrVec.add(new Attribute(
                        org.bouncycastle.asn1.cms.CMSAttributes.contentType,
                        new DERSet(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.data)));

                // Message-Digest attribute
                signedAttrVec.add(new Attribute(
                        org.bouncycastle.asn1.cms.CMSAttributes.messageDigest,
                        new DERSet(new org.bouncycastle.asn1.DEROctetString(contentHash))));

                // Signing-Time attribute
                signedAttrVec.add(new Attribute(
                        org.bouncycastle.asn1.cms.CMSAttributes.signingTime,
                        new DERSet(new org.bouncycastle.asn1.cms.Time(new java.util.Date()))));

                // ESS-signing-certificate-v2
                MessageDigest certDigest = MessageDigest.getInstance("SHA-256");
                byte[] certHash = certDigest.digest(signerCert.getEncoded());
                AlgorithmIdentifier hashAlgo = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
                ESSCertIDv2 essCertId = new ESSCertIDv2(hashAlgo, certHash);
                SigningCertificateV2 sigCertV2 = new SigningCertificateV2(new ESSCertIDv2[]{essCertId});
                signedAttrVec.add(new Attribute(
                        PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                        new DERSet(sigCertV2)));

                // 5. Encode the signed attributes and hash them
                org.bouncycastle.asn1.DERSet signedAttrSet = new DERSet(signedAttrVec);
                byte[] signedAttrBytes = signedAttrSet.getEncoded("DER");

                MessageDigest attrDigest = MessageDigest.getInstance("SHA-256");
                byte[] attrHash = attrDigest.digest(signedAttrBytes);

                // 6. Send the hash to the CSC provider for signing
                String hashB64 = Base64.getEncoder().encodeToString(attrHash);
                CscSignResult signResult = cscClient.signHash(
                        provider, accessToken, credentialId, sad,
                        List.of(hashB64), signAlgoOid);

                if (signResult.getSignatures() == null || signResult.getSignatures().isEmpty()) {
                    throw new IOException("CSC signHash returned no signatures");
                }

                byte[] rawSignature = Base64.getDecoder().decode(signResult.getSignatures().get(0));

                // 7. Build the complete CMS SignedData with the remote signature
                return buildCmsSignedData(contentBytes, signedAttrSet, rawSignature, signerCert, certChain);

            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("CSC CMS signing failed: " + e.getMessage(), e);
            }
        }

        private byte[] buildCmsSignedData(byte[] content, org.bouncycastle.asn1.ASN1Set signedAttrs,
                                           byte[] rawSignature, X509Certificate cert,
                                           List<X509Certificate> chain) throws Exception {
            // Determine algorithm OIDs
            String keyAlgo = cert.getPublicKey().getAlgorithm();
            org.bouncycastle.asn1.x509.AlgorithmIdentifier digestAlgId =
                    new org.bouncycastle.asn1.x509.AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);

            org.bouncycastle.asn1.x509.AlgorithmIdentifier sigAlgId;
            if ("EC".equals(keyAlgo)) {
                sigAlgId = new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.4.3.2")); // SHA256withECDSA
            } else {
                sigAlgId = new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        PKCSObjectIdentifiers.sha256WithRSAEncryption);
            }

            // Build SignerInfo
            org.bouncycastle.asn1.cms.IssuerAndSerialNumber issuerAndSerial =
                    new org.bouncycastle.asn1.cms.IssuerAndSerialNumber(
                            org.bouncycastle.asn1.x500.X500Name.getInstance(
                                    cert.getIssuerX500Principal().getEncoded()),
                            cert.getSerialNumber());

            org.bouncycastle.asn1.cms.SignerIdentifier signerIdentifier =
                    new org.bouncycastle.asn1.cms.SignerIdentifier(issuerAndSerial);

            org.bouncycastle.asn1.cms.SignerInfo signerInfo =
                    new org.bouncycastle.asn1.cms.SignerInfo(
                            signerIdentifier,
                            digestAlgId,
                            signedAttrs,
                            sigAlgId,
                            new org.bouncycastle.asn1.DEROctetString(rawSignature),
                            null); // no unsigned attributes

            // Build SignedData
            ASN1EncodableVector digestAlgs = new ASN1EncodableVector();
            digestAlgs.add(digestAlgId);

            org.bouncycastle.asn1.cms.ContentInfo contentInfo =
                    new org.bouncycastle.asn1.cms.ContentInfo(
                            org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.data, null); // detached

            ASN1EncodableVector signerInfos = new ASN1EncodableVector();
            signerInfos.add(signerInfo);

            // Certificate set
            ASN1EncodableVector certSet = new ASN1EncodableVector();
            for (X509Certificate c : chain) {
                certSet.add(org.bouncycastle.asn1.x509.Certificate.getInstance(c.getEncoded()));
            }

            org.bouncycastle.asn1.cms.SignedData signedData =
                    new org.bouncycastle.asn1.cms.SignedData(
                            new DERSet(digestAlgs),
                            contentInfo,
                            new org.bouncycastle.asn1.BERSet(certSet),
                            null, // CRLs
                            new DERSet(signerInfos));

            org.bouncycastle.asn1.cms.ContentInfo cmsContentInfo =
                    new org.bouncycastle.asn1.cms.ContentInfo(
                            PKCSObjectIdentifiers.signedData,
                            signedData);

            return cmsContentInfo.getEncoded("DER");
        }

        private AttributeTable buildSignedAttributes(X509Certificate cert) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] certHash = md.digest(cert.getEncoded());
            AlgorithmIdentifier hashAlgo = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
            ESSCertIDv2 essCertId = new ESSCertIDv2(hashAlgo, certHash);
            SigningCertificateV2 sigCertV2 = new SigningCertificateV2(new ESSCertIDv2[]{essCertId});

            ASN1EncodableVector v = new ASN1EncodableVector();
            v.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(sigCertV2)));
            return new AttributeTable(v);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private PDSignatureField findSignatureField(PDAcroForm acroForm, String fieldName) {
        for (PDField field : acroForm.getFieldTree()) {
            if (field instanceof PDSignatureField sf) {
                if (fieldName.equals(sf.getFullyQualifiedName()) || fieldName.equals(sf.getPartialName())) {
                    return sf;
                }
            }
        }
        return null;
    }

    private List<X509Certificate> parseCertChain(List<String> certsB64) {
        List<X509Certificate> chain = new ArrayList<>();
        if (certsB64 == null) return chain;
        for (String b64 : certsB64) {
            X509Certificate cert = cscApiClient.parseCertificate(b64);
            if (cert != null) chain.add(cert);
        }
        return chain;
    }

    private String extractCN(X509Certificate cert) {
        if (cert == null) return null;
        String dn = cert.getSubjectX500Principal().getName();
        var match = java.util.regex.Pattern.compile("CN=([^,]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(dn);
        return match.find() ? match.group(1).trim() : null;
    }
}
