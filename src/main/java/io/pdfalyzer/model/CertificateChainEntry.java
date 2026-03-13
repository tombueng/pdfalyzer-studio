package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertificateChainEntry {
    private int chainIndex;              // 0 = end-entity, last = root
    private String role;                 // END_ENTITY, INTERMEDIATE, ROOT
    private String subjectDN;
    private String issuerDN;
    private String serialNumber;
    private String notBefore;
    private String notAfter;
    private String signatureAlgorithm;
    private String publicKeyAlgorithm;
    private int publicKeyBitLength;
    @Builder.Default
    private List<String> keyUsage = new ArrayList<>();
    private boolean isCA;
    private int pathLengthConstraint;    // -1 if absent
    @Builder.Default
    private List<String> crlDistributionPoints = new ArrayList<>();
    @Builder.Default
    private List<String> ocspResponderUrls = new ArrayList<>();
    @Builder.Default
    private List<String> authorityInfoAccessUrls = new ArrayList<>();
    private String subjectKeyIdentifier;
    private String authorityKeyIdentifier;

    // Trust anchor resolution (filled by TrustValidationService)
    private boolean isTrustAnchor;
    private String trustListSource;      // "EUTL:AT", "AATL", null
    private String trustServiceName;
    private String trustServiceStatus;   // "granted", "withdrawn", etc.

    // Revocation status (filled by RevocationCheckService)
    private RevocationStatus revocationStatus;
}
