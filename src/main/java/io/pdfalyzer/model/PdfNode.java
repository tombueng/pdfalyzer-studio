package io.pdfalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PdfNode {
    private String id;
    private String name;
    private String type;
    private String icon;
    private String color;
    private String nodeCategory;
    private int objectNumber = -1;
    private int generationNumber = -1;
    private int pageIndex = -1;
    private double[] boundingBox;
    private Map<String, String> properties;
    private List<PdfNode> children = new ArrayList<>();
    private String cosType;
    private String rawValue;
    private boolean editable;
    private String valueType;
    private String keyPath;

    public PdfNode() {}

    public PdfNode(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public PdfNode(String id, String name, String type, String icon, String color) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.icon = icon;
        this.color = color;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getNodeCategory() { return nodeCategory; }
    public void setNodeCategory(String nodeCategory) { this.nodeCategory = nodeCategory; }

    public int getObjectNumber() { return objectNumber; }
    public void setObjectNumber(int objectNumber) { this.objectNumber = objectNumber; }

    public int getGenerationNumber() { return generationNumber; }
    public void setGenerationNumber(int generationNumber) { this.generationNumber = generationNumber; }

    public int getPageIndex() { return pageIndex; }
    public void setPageIndex(int pageIndex) { this.pageIndex = pageIndex; }

    public double[] getBoundingBox() { return boundingBox; }
    public void setBoundingBox(double[] boundingBox) { this.boundingBox = boundingBox; }

    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> properties) { this.properties = properties; }

    public void addProperty(String key, String value) {
        if (this.properties == null) {
            this.properties = new LinkedHashMap<>();
        }
        this.properties.put(key, value);
    }

    public List<PdfNode> getChildren() { return children; }
    public void setChildren(List<PdfNode> children) { this.children = children; }

    public void addChild(PdfNode child) {
        this.children.add(child);
    }

    public String getCosType() { return cosType; }
    public void setCosType(String cosType) { this.cosType = cosType; }

    public String getRawValue() { return rawValue; }
    public void setRawValue(String rawValue) { this.rawValue = rawValue; }

    public boolean isEditable() { return editable; }
    public void setEditable(boolean editable) { this.editable = editable; }

    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }

    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
}
