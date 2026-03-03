package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class PdfNode {
    private String id;
    private String name;
    private String type;
    private String icon;
    private String color;
    private String nodeCategory;
    @Builder.Default
    private int objectNumber = -1;
    @Builder.Default
    private int generationNumber = -1;
    @Builder.Default
    private int pageIndex = -1;
    private double[] boundingBox;
    private Map<String, String> properties;
    @Builder.Default
    private List<PdfNode> children = new ArrayList<>();
    private String cosType;
    private String rawValue;
    private boolean editable;
    private String valueType;
    private String keyPath;

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

    public void addProperty(String key, String value) {
        if (this.properties == null) {
            this.properties = new LinkedHashMap<>();
        }
        this.properties.put(key, value);
    }

    public void addChild(PdfNode child) {
        this.children.add(child);
    }
}
