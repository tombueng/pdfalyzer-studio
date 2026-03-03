package io.pdfalyzer.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data 
@Builder
public class FormFieldRequest {
    private String fieldType;
    private String fieldName;
    private int pageIndex;
    private double x;
    private double y;
    private double width;
    private double height;
    private Map<String, Object> options;
}
