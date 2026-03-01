package io.pdfalyzer.model;

import java.util.List;

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

    public int getObjectNumber() { return objectNumber; }
    public void setObjectNumber(int objectNumber) { this.objectNumber = objectNumber; }

    public int getGenerationNumber() { return generationNumber; }
    public void setGenerationNumber(int generationNumber) { this.generationNumber = generationNumber; }

    public List<String> getKeyPath() { return keyPath; }
    public void setKeyPath(List<String> keyPath) { this.keyPath = keyPath; }

    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getTargetScope() { return targetScope; }
    public void setTargetScope(String targetScope) { this.targetScope = targetScope; }
}
