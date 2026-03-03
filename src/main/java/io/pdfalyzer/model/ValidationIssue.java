package io.pdfalyzer.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
public class ValidationIssue {
    @Getter
    @Setter
    private String severity;
    @Getter
    @Setter
    private String ruleId;
    @Getter
    @Setter
    private String message;
    @Getter
    @Setter
    private String specReference;
    @Getter
    @Setter
    private String location;
    @Getter
    @Setter
    private String category;

    public ValidationIssue(String severity, String ruleId, String message,
                           String specReference, String location, String category) {
        this.severity = severity;
        this.ruleId = ruleId;
        this.message = message;
        this.specReference = specReference;
        this.location = location;
        this.category = category;
    }
}
