package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrustValidationCheck {
    private String checkName;    // CERT_VALIDITY, CHAIN_COMPLETE, TRUST_ANCHOR,
                                 // REVOCATION, BYTE_RANGE, DSS_PRESENT, DOCMDP
    private String status;       // PASS, FAIL, WARNING, SKIP
    private String message;
    private int signatureIndex;  // which signature this check relates to
}
