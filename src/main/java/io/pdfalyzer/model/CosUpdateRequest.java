package io.pdfalyzer.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CosUpdateRequest {
    private int objectNumber;
    private int generationNumber;
    private List<String> keyPath;
    private String newValue;
    private String valueType;
    // operation: "update" (default), "add" or "remove"
    private String operation;
    // optional target scope (e.g., "docinfo") for roots without indirect object ref
    private String targetScope;
}
