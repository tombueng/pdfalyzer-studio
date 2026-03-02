package io.pdfalyzer.diagnostics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Represents a single PDF diagnostic finding.
 * This is the JSON-serializable result of a diagnostic check.
 */
public class PdfFinding {
    
    @JsonProperty("issue_id")
    private String issueId;
    
    @JsonProperty("issue_name")
    private String issueName;
    
    @JsonProperty("severity")
    private Severity severity;
    
    @JsonProperty("description")
    private String description;
    
    @JsonProperty("affected_objects")
    private List<String> affectedObjects;
    
    @JsonProperty("fix_may_lose_data")
    private boolean fixMayLoseData;
    
    @JsonProperty("is_detected")
    private boolean isDetected;
    
    @JsonProperty("details")
    private Map<String, Object> details;
    
    @JsonProperty("fix_recommendations")
    private List<String> fixRecommendations;
    
    @JsonProperty("specification_references")
    private List<String> specificationReferences;
    
    @JsonProperty("stack_overflow_references")
    private List<String> stackOverflowReferences;

    public enum Severity {
        CRITICAL("Critical - Must fix"),
        MAJOR("Major - Should fix"),
        MINOR("Minor - Nice to fix");
        
        private final String description;
        
        Severity(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }

    // Constructors
    public PdfFinding() {
    }

    public PdfFinding(String issueId, String issueName) {
        this.issueId = issueId;
        this.issueName = issueName;
    }

    // Getters and Setters
    public String getIssueId() {
        return issueId;
    }

    public void setIssueId(String issueId) {
        this.issueId = issueId;
    }

    public String getIssueName() {
        return issueName;
    }

    public void setIssueName(String issueName) {
        this.issueName = issueName;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getAffectedObjects() {
        return affectedObjects;
    }

    public void setAffectedObjects(List<String> affectedObjects) {
        this.affectedObjects = affectedObjects;
    }

    public boolean isFixMayLoseData() {
        return fixMayLoseData;
    }

    public void setFixMayLoseData(boolean fixMayLoseData) {
        this.fixMayLoseData = fixMayLoseData;
    }

    public boolean isDetected() {
        return isDetected;
    }

    public void setDetected(boolean detected) {
        isDetected = detected;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public List<String> getFixRecommendations() {
        return fixRecommendations;
    }

    public void setFixRecommendations(List<String> fixRecommendations) {
        this.fixRecommendations = fixRecommendations;
    }

    public List<String> getSpecificationReferences() {
        return specificationReferences;
    }

    public void setSpecificationReferences(List<String> specificationReferences) {
        this.specificationReferences = specificationReferences;
    }

    public List<String> getStackOverflowReferences() {
        return stackOverflowReferences;
    }

    public void setStackOverflowReferences(List<String> stackOverflowReferences) {
        this.stackOverflowReferences = stackOverflowReferences;
    }

    @Override
    public String toString() {
        return "PdfFinding{" +
                "issueId='" + issueId + '\'' +
                ", issueName='" + issueName + '\'' +
                ", severity=" + severity +
                ", isDetected=" + isDetected +
                ", affectedObjects=" + affectedObjects +
                ", fixMayLoseData=" + fixMayLoseData +
                '}';
    }
}
