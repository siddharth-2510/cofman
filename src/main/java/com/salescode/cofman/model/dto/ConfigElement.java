package com.salescode.cofman.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.salescode.cofman.model.enums.ElementPattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a single configuration element with its actual value.
 * This is the in-memory working object that combines metadata (from _meta.json)
 * with the actual value (from ALL.json).
 * 
 * Used during processing - NOT for serialization to files.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class ConfigElement {
    
    private String name;
    private ElementPattern pattern;
    private String group;
    private JsonNode value;
    
    /**
     * Create from MetaElement (without value)
     */
    public static ConfigElement fromMeta(MetaElement meta) {
        return new ConfigElement(meta.getName(), meta.getPattern(), meta.getGroup(), null);
    }
    
    /**
     * Create from MetaElement with value
     */
    public static ConfigElement fromMeta(MetaElement meta, JsonNode value) {
        return new ConfigElement(meta.getName(), meta.getPattern(), meta.getGroup(), value);
    }
    
    /**
     * Convert to MetaElement (drops value)
     */
    public MetaElement toMetaElement() {
        return new MetaElement(name, pattern, group);
    }
    
    public boolean hasGroup() {
        return group != null && !group.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ConfigElement{" +
                "name='" + name + '\'' +
                ", pattern=" + pattern +
                ", group='" + group + '\'' +
                ", value=" + (value != null ? value.toString().substring(0, Math.min(50, value.toString().length())) + "..." : "null") +
                '}';
    }
}
