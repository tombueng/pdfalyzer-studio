package io.pdfalyzer.model;

public class ValidationIssue {
    private String severity;
    private String ruleId;
    private String message;
    private String specReference;
    private String location;
    private String category;

    public ValidationIssue() {}

    public ValidationIssue(String severity, String ruleId, String message,
                           String specReference, String location, String category) {
        this.severity = severity;
        this.ruleId = ruleId;
        this.message = message;
        this.specReference = specReference;
        this.location = location;
        this.category = category;
    }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSpecReference() { return specReference; }
    public void setSpecReference(String specReference) { this.specReference = specReference; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
