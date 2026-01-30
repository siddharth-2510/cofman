package com.salescode.cofman.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.salescode.cofman.model.enums.ElementPattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a single element entry in _meta.json.
 * Contains only metadata (name, pattern, group), NOT the actual value.
 * Values are stored in individual ALL.json files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class MetaElement {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("pattern")
    private ElementPattern pattern;
    
    @JsonProperty("group")
    private String group;
    
    public MetaElement() {
    }
    
    public MetaElement(String name, ElementPattern pattern, String group) {
        this.name = name;
        this.pattern = pattern;
        this.group = group;
    }
    
    // Builder-style setters for fluent API
    public static MetaElement of(String name, ElementPattern pattern) {
        return new MetaElement(name, pattern, null);
    }
    
    public static MetaElement of(String name, ElementPattern pattern, String group) {
        return new MetaElement(name, pattern, group);
    }
    
    @Override
    public String toString() {
        return "MetaElement{" +
                "name='" + name + '\'' +
                ", pattern=" + pattern +
                ", group='" + group + '\'' +
                '}';
    }
}
