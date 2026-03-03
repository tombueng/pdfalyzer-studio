package io.pdfalyzer.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@NoArgsConstructor
public class FormFieldRequest {
    @Getter
    @Setter
    private String fieldType;
    @Getter
    @Setter
    private String fieldName;
    @Getter
    @Setter
    private int pageIndex;
    @Getter
    @Setter
    private double x;
    @Getter
    @Setter
    private double y;
    @Getter
    @Setter
    private double width;
    @Getter
    @Setter
    private double height;
    @Getter
    @Setter
    private Map<String, Object> options;
}
