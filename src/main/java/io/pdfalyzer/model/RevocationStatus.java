package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevocationStatus {
    private String status;           // GOOD, REVOKED, UNKNOWN, NOT_CHECKED, ERROR
    private String checkedVia;       // OCSP, CRL, DSS_OCSP, DSS_CRL
    private String checkedAt;        // ISO timestamp
    private String revokedAt;        // ISO timestamp, null if not revoked
    private String revocationReason;
    private String errorMessage;
    private String ocspResponderUrl;
    private String crlUrl;
}
