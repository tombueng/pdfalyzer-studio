package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfSignedCertRequest {
    private String commonName;
    private String organization;
    private String organizationalUnit;
    private String country;
    @Builder.Default
    private int validityDays = 365;
    @Builder.Default
    private String keyAlgorithm = "RSA";
    @Builder.Default
    private int keySize = 2048;
}
