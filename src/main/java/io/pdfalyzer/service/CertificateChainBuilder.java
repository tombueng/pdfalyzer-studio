package io.pdfalyzer.service;

import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Store;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;

import io.pdfalyzer.model.CertificateChainEntry;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts and orders the full certificate chain from CMS/PKCS#7 signed data.
 * Builds chain from end-entity (signer) through intermediates to root.
 */
@Service
@Slf4j
public class CertificateChainBuilder {

    private static final String[] KEY_USAGE_NAMES = {
            "digitalSignature", "nonRepudiation", "keyEncipherment",
            "dataEncipherment", "keyAgreement", "keyCertSign",
            "crlSign", "encipherOnly", "decipherOnly"
    };

    /**
     * Extract and order the full certificate chain from a CMS signature.
     */
    @SuppressWarnings("unchecked")
    public List<CertificateChainEntry> buildChainFromCms(CMSSignedData cmsData, SignerInformation signer) {
        try {
            Store<X509CertificateHolder> certStore = cmsData.getCertificates();
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();

            // Get the signer's certificate
            Collection<X509CertificateHolder> signerCerts = certStore.getMatches(signer.getSID());
            if (signerCerts.isEmpty()) {
                log.debug("No signer certificate found in CMS data");
                return List.of();
            }
            X509Certificate signerCert = converter.getCertificate(signerCerts.iterator().next());

            // Get ALL certificates from the CMS store
            Collection<X509CertificateHolder> allHolders = certStore.getMatches(null);
            List<X509Certificate> allCerts = new ArrayList<>();
            for (X509CertificateHolder holder : allHolders) {
                allCerts.add(converter.getCertificate(holder));
            }

            // Order into chain: end-entity -> intermediates -> root
            List<X509Certificate> orderedChain = orderChain(signerCert, allCerts);

            // Convert to CertificateChainEntry objects
            List<CertificateChainEntry> entries = new ArrayList<>();
            for (int i = 0; i < orderedChain.size(); i++) {
                X509Certificate cert = orderedChain.get(i);
                String role = determineRole(cert, i, orderedChain.size());
                entries.add(toCertificateChainEntry(cert, i, role));
            }

            log.debug("Built certificate chain with {} entries", entries.size());
            return entries;

        } catch (Exception e) {
            log.debug("Error building certificate chain from CMS: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract the TSA certificate chain from a CMS signature's timestamp token (unsigned attribute).
     * Returns an empty list if no timestamp token is present.
     */
    @SuppressWarnings("unchecked")
    public TsaChainResult extractTsaChain(CMSSignedData cmsData, SignerInformation signer) {
        try {
            AttributeTable unsignedAttrs = signer.getUnsignedAttributes();
            if (unsignedAttrs == null) {
                return TsaChainResult.empty();
            }

            Attribute tsAttr = unsignedAttrs.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
            if (tsAttr == null) {
                return TsaChainResult.empty();
            }

            // Parse the timestamp token
            byte[] tsTokenBytes = tsAttr.getAttrValues().getObjectAt(0).toASN1Primitive().getEncoded();
            TimeStampToken tsToken = new TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(tsTokenBytes));

            // Get TSA signing time
            String tsaTime = tsToken.getTimeStampInfo().getGenTime().toInstant()
                    .atOffset(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            // Extract TSA signer certificate
            Store<X509CertificateHolder> tsaCertStore = tsToken.getCertificates();
            SignerInformation tsaSigner = tsToken.toCMSSignedData().getSignerInfos()
                    .getSigners().iterator().next();

            Collection<X509CertificateHolder> tsaSignerCerts = tsaCertStore.getMatches(tsaSigner.getSID());
            if (tsaSignerCerts.isEmpty()) {
                log.debug("No TSA signer certificate found in timestamp token");
                return new TsaChainResult(tsaTime, List.of());
            }

            X509Certificate tsaSignerCert = new JcaX509CertificateConverter()
                    .getCertificate(tsaSignerCerts.iterator().next());

            // Get all certs from TSA token
            Collection<X509CertificateHolder> allTsaHolders = tsaCertStore.getMatches(null);
            List<X509Certificate> allTsaCerts = new ArrayList<>();
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            for (X509CertificateHolder holder : allTsaHolders) {
                allTsaCerts.add(converter.getCertificate(holder));
            }

            // Order into chain
            List<X509Certificate> orderedChain = orderChain(tsaSignerCert, allTsaCerts);

            // Convert to entries
            List<CertificateChainEntry> entries = new ArrayList<>();
            for (int i = 0; i < orderedChain.size(); i++) {
                X509Certificate cert = orderedChain.get(i);
                String role = determineRole(cert, i, orderedChain.size());
                entries.add(toCertificateChainEntry(cert, i, role));
            }

            log.debug("Extracted TSA chain with {} entries, signing time: {}", entries.size(), tsaTime);
            return new TsaChainResult(tsaTime, entries);

        } catch (Exception e) {
            log.debug("Error extracting TSA chain: {}", e.getMessage());
            return TsaChainResult.empty();
        }
    }

    /**
     * Result of TSA chain extraction, containing the signing time and chain entries.
     */
    public record TsaChainResult(String tsaSigningTime, List<CertificateChainEntry> chain) {
        public boolean hasTsa() { return chain != null && !chain.isEmpty(); }
        public static TsaChainResult empty() { return new TsaChainResult(null, List.of()); }
    }

    /**
     * Convert a single X509Certificate to a CertificateChainEntry with all extension details.
     */
    public CertificateChainEntry toCertificateChainEntry(X509Certificate cert, int index, String role) {
        return CertificateChainEntry.builder()
                .chainIndex(index)
                .role(role)
                .subjectDN(cert.getSubjectX500Principal().getName())
                .issuerDN(cert.getIssuerX500Principal().getName())
                .serialNumber(cert.getSerialNumber().toString(16))
                .notBefore(cert.getNotBefore().toInstant()
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .notAfter(cert.getNotAfter().toInstant()
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .signatureAlgorithm(cert.getSigAlgName())
                .publicKeyAlgorithm(cert.getPublicKey().getAlgorithm())
                .publicKeyBitLength(getKeyBitLength(cert))
                .keyUsage(extractKeyUsage(cert))
                .isCA(cert.getBasicConstraints() >= 0)
                .pathLengthConstraint(cert.getBasicConstraints())
                .crlDistributionPoints(extractCrlDistributionPoints(cert))
                .ocspResponderUrls(extractOcspUrls(cert))
                .authorityInfoAccessUrls(extractAiaUrls(cert))
                .subjectKeyIdentifier(extractSubjectKeyIdentifier(cert))
                .authorityKeyIdentifier(extractAuthorityKeyIdentifier(cert))
                .build();
    }

    /**
     * Order certificates into a chain: end-entity first, root last.
     * Matches issuer DN of cert N to subject DN of cert N+1.
     */
    private List<X509Certificate> orderChain(X509Certificate endEntity, List<X509Certificate> allCerts) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(endEntity);

        X509Certificate current = endEntity;
        int maxDepth = allCerts.size(); // prevent infinite loops

        for (int depth = 0; depth < maxDepth; depth++) {
            X500Principal issuer = current.getIssuerX500Principal();
            X500Principal subject = current.getSubjectX500Principal();

            // If self-signed, we've reached the root
            if (issuer.equals(subject)) {
                break;
            }

            // Find the issuer in our cert collection
            X509Certificate issuerCert = findIssuer(current, allCerts, chain);
            if (issuerCert == null) {
                log.debug("Chain incomplete: no issuer found for {}", subject.getName());
                break;
            }

            chain.add(issuerCert);
            current = issuerCert;
        }

        return chain;
    }

    /**
     * Find the issuing certificate for a given cert, preferring AKI/SKI match over DN-only match.
     */
    private X509Certificate findIssuer(X509Certificate cert, List<X509Certificate> candidates,
                                       List<X509Certificate> alreadyInChain) {
        String aki = extractAuthorityKeyIdentifier(cert);
        X500Principal issuerDN = cert.getIssuerX500Principal();

        X509Certificate dnMatch = null;

        for (X509Certificate candidate : candidates) {
            if (alreadyInChain.contains(candidate)) continue;
            if (!candidate.getSubjectX500Principal().equals(issuerDN)) continue;

            // DN matches — check AKI/SKI if available
            if (aki != null) {
                String ski = extractSubjectKeyIdentifier(candidate);
                if (aki.equals(ski)) {
                    return candidate; // exact AKI/SKI match
                }
            }

            if (dnMatch == null) {
                dnMatch = candidate;
            }
        }

        return dnMatch;
    }

    private String determineRole(X509Certificate cert, int index, int chainLength) {
        if (index == 0) return "END_ENTITY";
        if (cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) return "ROOT";
        if (index == chainLength - 1) return "ROOT"; // last cert is treated as root/trust anchor
        return "INTERMEDIATE";
    }

    private int getKeyBitLength(X509Certificate cert) {
        try {
            String algo = cert.getPublicKey().getAlgorithm();
            if ("RSA".equals(algo)) {
                java.security.interfaces.RSAPublicKey rsaKey =
                        (java.security.interfaces.RSAPublicKey) cert.getPublicKey();
                return rsaKey.getModulus().bitLength();
            }
            if ("EC".equals(algo)) {
                java.security.interfaces.ECPublicKey ecKey =
                        (java.security.interfaces.ECPublicKey) cert.getPublicKey();
                return ecKey.getParams().getOrder().bitLength();
            }
            if ("Ed25519".equalsIgnoreCase(algo)) return 256;
            if ("Ed448".equalsIgnoreCase(algo)) return 448;
        } catch (Exception e) {
            log.debug("Could not determine key bit length: {}", e.getMessage());
        }
        return 0;
    }

    private List<String> extractKeyUsage(X509Certificate cert) {
        List<String> usages = new ArrayList<>();
        boolean[] ku = cert.getKeyUsage();
        if (ku == null) return usages;
        for (int i = 0; i < ku.length && i < KEY_USAGE_NAMES.length; i++) {
            if (ku[i]) {
                usages.add(KEY_USAGE_NAMES[i]);
            }
        }
        return usages;
    }

    /**
     * Extract CRL Distribution Point URLs from certificate extension.
     */
    public List<String> extractCrlDistributionPoints(X509Certificate cert) {
        List<String> urls = new ArrayList<>();
        try {
            byte[] extValue = cert.getExtensionValue(Extension.cRLDistributionPoints.getId());
            if (extValue == null) return urls;

            ASN1OctetString octetString = ASN1OctetString.getInstance(extValue);
            CRLDistPoint distPoint = CRLDistPoint.getInstance(
                    ASN1Primitive.fromByteArray(octetString.getOctets()));

            for (DistributionPoint dp : distPoint.getDistributionPoints()) {
                DistributionPointName dpName = dp.getDistributionPoint();
                if (dpName != null && dpName.getType() == DistributionPointName.FULL_NAME) {
                    GeneralNames names = (GeneralNames) dpName.getName();
                    for (GeneralName name : names.getNames()) {
                        if (name.getTagNo() == GeneralName.uniformResourceIdentifier) {
                            urls.add(name.getName().toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting CRL distribution points: {}", e.getMessage());
        }
        return urls;
    }

    /**
     * Extract OCSP responder URLs from Authority Information Access extension.
     */
    public List<String> extractOcspUrls(X509Certificate cert) {
        List<String> urls = new ArrayList<>();
        try {
            byte[] extValue = cert.getExtensionValue(Extension.authorityInfoAccess.getId());
            if (extValue == null) return urls;

            ASN1OctetString octetString = ASN1OctetString.getInstance(extValue);
            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(
                    ASN1Primitive.fromByteArray(octetString.getOctets()));

            for (AccessDescription ad : aia.getAccessDescriptions()) {
                if (AccessDescription.id_ad_ocsp.equals(ad.getAccessMethod())) {
                    GeneralName gn = ad.getAccessLocation();
                    if (gn.getTagNo() == GeneralName.uniformResourceIdentifier) {
                        urls.add(gn.getName().toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting OCSP URLs: {}", e.getMessage());
        }
        return urls;
    }

    /**
     * Extract all Authority Information Access URLs (both OCSP and CA issuers).
     */
    public List<String> extractAiaUrls(X509Certificate cert) {
        List<String> urls = new ArrayList<>();
        try {
            byte[] extValue = cert.getExtensionValue(Extension.authorityInfoAccess.getId());
            if (extValue == null) return urls;

            ASN1OctetString octetString = ASN1OctetString.getInstance(extValue);
            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(
                    ASN1Primitive.fromByteArray(octetString.getOctets()));

            for (AccessDescription ad : aia.getAccessDescriptions()) {
                GeneralName gn = ad.getAccessLocation();
                if (gn.getTagNo() == GeneralName.uniformResourceIdentifier) {
                    urls.add(gn.getName().toString());
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting AIA URLs: {}", e.getMessage());
        }
        return urls;
    }

    private String extractSubjectKeyIdentifier(X509Certificate cert) {
        try {
            byte[] extValue = cert.getExtensionValue(Extension.subjectKeyIdentifier.getId());
            if (extValue == null) return null;

            ASN1OctetString octetString = ASN1OctetString.getInstance(extValue);
            SubjectKeyIdentifier ski = SubjectKeyIdentifier.getInstance(
                    ASN1Primitive.fromByteArray(octetString.getOctets()));
            return Hex.toHexString(ski.getKeyIdentifier());
        } catch (Exception e) {
            log.debug("Error extracting SKI: {}", e.getMessage());
            return null;
        }
    }

    private String extractAuthorityKeyIdentifier(X509Certificate cert) {
        try {
            byte[] extValue = cert.getExtensionValue(Extension.authorityKeyIdentifier.getId());
            if (extValue == null) return null;

            ASN1OctetString octetString = ASN1OctetString.getInstance(extValue);
            AuthorityKeyIdentifier aki = AuthorityKeyIdentifier.getInstance(
                    ASN1Primitive.fromByteArray(octetString.getOctets()));
            byte[] keyId = aki.getKeyIdentifier();
            return keyId != null ? Hex.toHexString(keyId) : null;
        } catch (Exception e) {
            log.debug("Error extracting AKI: {}", e.getMessage());
            return null;
        }
    }
}
