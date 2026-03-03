package io.pdfalyzer.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
public class FontInfo {
    @Getter
    @Setter
    private String fontName;
    @Getter
    @Setter
    private String fontType;
    @Getter
    @Setter
    private String encoding;
    @Getter
    @Setter
    private boolean embedded;
    @Getter
    @Setter
    private boolean subset;
    @Getter
    @Setter
    private int glyphCount;
    @Getter
    @Setter
    private List<String> issues = new ArrayList<>();
    @Getter
    @Setter
    private int pageIndex;
    @Getter
    @Setter
    private String objectId;
    @Getter
    @Setter
    private int objectNumber = -1;
    @Getter
    @Setter
    private int generationNumber = -1;
    @Getter
    @Setter
    private String usageContext;
    @Getter
    @Setter
    private String fixSuggestion;

    public void addIssue(String issue) { this.issues.add(issue); }
}
