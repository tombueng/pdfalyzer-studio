package io.pdfalyzer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
public class PdfNode {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String type;
    @Getter
    @Setter
    private String icon;
    @Getter
    @Setter
    private String color;
    @Getter
    @Setter
    private String nodeCategory;
    @Getter
    @Setter
    private int objectNumber = -1;
    @Getter
    @Setter
    private int generationNumber = -1;
    @Getter
    @Setter
    private int pageIndex = -1;
    @Getter
    @Setter
    private double[] boundingBox;
    @Getter
    @Setter
    private Map<String, String> properties;
    @Getter
    @Setter
    private List<PdfNode> children = new ArrayList<>();
    @Getter
    @Setter
    private String cosType;
    @Getter
    @Setter
    private String rawValue;
    @Getter
    @Setter
    private boolean editable;
    @Getter
    @Setter
    private String valueType;
    @Getter
    @Setter
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
