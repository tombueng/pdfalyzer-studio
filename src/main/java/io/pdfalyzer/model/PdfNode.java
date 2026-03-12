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
    private String badge;
    private String badgeColor;

    public PdfNode(String name, String type) {
        this.name = name;
        this.type = type;
        this.children = new ArrayList<>();
        this.objectNumber = -1;
        this.generationNumber = -1;
        this.pageIndex = -1;
    }

    public PdfNode(String id, String name, String type, String icon, String color) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.icon = icon;
        this.color = color;
        this.children = new ArrayList<>();
        this.objectNumber = -1;
        this.generationNumber = -1;
        this.pageIndex = -1;
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

    /**
     * Copies this node tree up to {@code maxDepth} levels deep, appending the
     * given suffix to all IDs to avoid duplicate DOM identifiers.
     * Children beyond the depth limit are omitted.
     */
    public PdfNode deepCopy(String idSuffix, int maxDepth) {
        PdfNode copy = new PdfNode(
                id != null ? id + idSuffix : null,
                name, type, icon, color);
        copy.nodeCategory = nodeCategory;
        copy.objectNumber = objectNumber;
        copy.generationNumber = generationNumber;
        copy.pageIndex = pageIndex;
        copy.boundingBox = boundingBox;
        copy.cosType = cosType;
        copy.rawValue = rawValue;
        copy.editable = editable;
        copy.valueType = valueType;
        copy.keyPath = keyPath;
        copy.badge = badge;
        copy.badgeColor = badgeColor;
        if (properties != null) {
            copy.properties = new LinkedHashMap<>(properties);
        }
        if (maxDepth > 0) {
            for (PdfNode child : children) {
                copy.children.add(child.deepCopy(idSuffix, maxDepth - 1));
            }
        }
        return copy;
    }
}
