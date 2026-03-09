package io.pdfalyzer.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CscCredential {
    private String credentialId;
    private String description;
    private String scal;           // "1" or "2"
    private String keyAlgorithm;   // OID or name
    private int keyLength;
    private String subjectDN;
    private String issuerDN;
    private String serialNumber;
    private String validFrom;
    private String validUntil;
    private List<String> certificates; // Base64-encoded certificate chain
    private List<String> supportedAlgos; // Signing algorithm OIDs
    private boolean multisign;
    private String status;         // "enabled", "disabled"
}
