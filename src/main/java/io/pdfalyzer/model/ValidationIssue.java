package io.pdfalyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class ValidationIssue {
    private String severity;
    private String ruleId;
    private String message;
    private String specReference;
    private String location;
    private String category;
}
