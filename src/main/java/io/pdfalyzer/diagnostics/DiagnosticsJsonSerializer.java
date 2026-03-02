package io.pdfalyzer.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.pdfalyzer.diagnostics.model.PdfFinding;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Utility for serializing diagnostic findings to JSON format.
 * Provides flexible output options for different use cases.
 */
public class DiagnosticsJsonSerializer {
    
    private final ObjectMapper objectMapper;
    
    public DiagnosticsJsonSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    /**
     * Serialize a single finding to JSON string
     */
    public String serializeFinding(PdfFinding finding) throws IOException {
        return objectMapper.writeValueAsString(finding);
    }
    
    /**
     * Serialize multiple findings to JSON array
     */
    public String serializeFindings(List<PdfFinding> findings) throws IOException {
        return objectMapper.writeValueAsString(findings);
    }
    
    /**
     * Serialize a complete diagnostic report
     */
    public String serializeDiagnosticReport(
            String filename,
            List<PdfFinding> findings,
            Map<String, Object> summary) throws IOException {
        
        Map<String, Object> report = Map.of(
                "filename", filename,
                "timestamp", System.currentTimeMillis(),
                "summary", summary,
                "findings", findings
        );
        
        return objectMapper.writeValueAsString(report);
    }
    
    /**
     * Get JSON as bytes (for file output)
     */
    public byte[] serializeFindingsAsBytes(List<PdfFinding> findings) throws IOException {
        return objectMapper.writeValueAsBytes(findings);
    }
    
    /**
     * Deserialize JSON back to PdfFinding
     */
    public PdfFinding deserializeFinding(String json) throws IOException {
        return objectMapper.readValue(json, PdfFinding.class);
    }
    
    /**
     * Deserialize JSON array back to list of findings
     */
    public List<PdfFinding> deserializeFindings(String json) throws IOException {
        return objectMapper.readValue(json, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, PdfFinding.class));
    }
}
