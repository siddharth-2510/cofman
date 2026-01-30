package com.salescode.cofman.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the _meta.json file structure.
 * This file lives at: configs/{lob}/{domain_name}/{domain_type}/_meta.json
 * 
 * Contains ordering and pattern information for reconstructing the original JSON array.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class MetaFile {
    
    @JsonProperty("version")
    private String version = "1.0";
    
    @JsonProperty("domain_name")
    private String domainName;
    
    @JsonProperty("domain_type")
    private String domainType;
    
    @JsonProperty("elements")
    private List<MetaElement> elements;
    
    public MetaFile() {
        this.elements = new ArrayList<>();
    }
    
    public MetaFile(String domainName, String domainType) {
        this();
        this.domainName = domainName;
        this.domainType = domainType;
    }
    
    public MetaFile(String domainName, String domainType, List<MetaElement> elements) {
        this.domainName = domainName;
        this.domainType = domainType;
        this.elements = elements != null ? elements : new ArrayList<>();
    }
    
    public void addElement(MetaElement element) {
        if (this.elements == null) {
            this.elements = new ArrayList<>();
        }
        this.elements.add(element);
    }
    
    public void removeElement(String elementName) {
        if (this.elements != null) {
            this.elements.removeIf(e -> e.getName().equals(elementName));
        }
    }
    
    public MetaElement findElement(String elementName) {
        if (this.elements == null) {
            return null;
        }
        return this.elements.stream()
                .filter(e -> e.getName().equals(elementName))
                .findFirst()
                .orElse(null);
    }
    
    public boolean hasElement(String elementName) {
        return findElement(elementName) != null;
    }
    
    @Override
    public String toString() {
        return "MetaFile{" +
                "version='" + version + '\'' +
                ", domainName='" + domainName + '\'' +
                ", domainType='" + domainType + '\'' +
                ", elements=" + elements +
                '}';
    }
}
