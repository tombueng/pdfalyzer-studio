package io.pdfalyzer.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FontInfo {
    private String fontName;
    private String fontType;
    private String encoding;
    private boolean embedded;
    private boolean subset;
    private int glyphCount;
    @Builder.Default
    private List<String> issues = new ArrayList<>();
    private int pageIndex;
    private String objectId;
    @Builder.Default
    private int objectNumber = -1;
    @Builder.Default
    private int generationNumber = -1;
    private String usageContext;
    private String fixSuggestion;

    public void addIssue(String issue) {
        this.issues.add(issue);
    }
}
