package io.pdfalyzer.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class CosUpdateRequest {
    @Getter
    @Setter
    private int objectNumber;
    @Getter
    @Setter
    private int generationNumber;
    @Getter
    @Setter
    private List<String> keyPath;
    @Getter
    @Setter
    private String newValue;
    @Getter
    @Setter
    private String valueType;
    // operation: "update" (default), "add" or "remove"
    @Getter
    @Setter
    private String operation;
    // optional target scope (e.g., "docinfo") for roots without indirect object ref
    @Getter
    @Setter
    private String targetScope;
}
