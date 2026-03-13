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
public class TrustValidationReport {
    @Builder.Default
    private List<TrustValidationCheck> checks = new ArrayList<>();
    private String overallStatus;    // VALID, INDETERMINATE, INVALID
    private String overallMessage;
    private TrustListStatus eutlStatus;
    private TrustListStatus aatlStatus;
}
