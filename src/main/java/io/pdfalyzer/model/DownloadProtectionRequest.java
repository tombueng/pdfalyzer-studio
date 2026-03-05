package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for the protected-download endpoint.
 *
 * mode:
 *   "keep"   – re-apply the original encryption using the stored unlock password
 *   "custom" – encrypt with the supplied userPassword / ownerPassword / algorithm
 *   "none"   – return the decrypted PDF as-is
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadProtectionRequest {
    private String mode;          // "keep" | "custom" | "none"
    private String userPassword;  // used when mode="custom"
    private String ownerPassword; // used when mode="custom"; falls back to userPassword when blank
    private String algorithm;     // "AES-256" | "AES-128" | "RC4-128" | "RC4-40"; used when mode="custom"
}
