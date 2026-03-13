package io.pdfalyzer.model;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holds parsed key material for a signing session.
 * This object is NEVER serialized to JSON — it stays server-side only.
 * Private keys are held in memory and cleared when the session expires.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SigningKeyMaterial {
    private String sessionKeyId;
    private PrivateKey privateKey;
    private X509Certificate certificate;
    @Builder.Default
    private List<X509Certificate> chain = new ArrayList<>();
    private String sourceDescription;
    private long uploadedAt;

    public boolean isReadyToSign() {
        return privateKey != null && certificate != null;
    }

    public boolean hasPrivateKey() {
        return privateKey != null;
    }

    public boolean hasCertificate() {
        return certificate != null;
    }

    public boolean hasCertChain() {
        return chain != null && chain.size() > 1;
    }
}
