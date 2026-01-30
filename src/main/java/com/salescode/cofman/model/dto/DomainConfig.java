package com.salescode.cofman.model.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.salescode.cofman.model.enums.Action;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Safe JSON-mapped DomainConfig
 * Compatible with Hibernate 6 JSON + legacy DB data
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DomainConfig {

    /**
     * Accept both camelCase and snake_case
     */
    @JsonAlias({"lob"})
    private String lob;

    @JsonAlias({"domainName", "domain_name"})
    private String domainName;

    @JsonAlias({"domainType", "domain_type"})
    private String domainType;

    /**
     * Case-insensitive enum handling
     * Missing value defaults to INSERT
     */
    @JsonSetter(nulls = Nulls.SKIP)
    private Action action = Action.INSERT;

    @JsonAlias({"env", "environment"})
    private String env;

    /**
     * Always initialize collections
     */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<ConfigElement> elements = new ArrayList<>();

    /* ------------------ Safety helpers ------------------ */


    /* ------------------ Utility methods ------------------ */

    public void addElement(ConfigElement element) {
        if (element != null) {
            this.elements.add(element);
        }
    }

    public void removeElement(String elementName) {
        if (elementName == null) return;
        elements.removeIf(e -> elementName.equals(e.getName()));
    }

    public ConfigElement findElement(String elementName) {
        if (elementName == null) return null;
        return elements.stream()
                .filter(e -> elementName.equals(e.getName()))
                .findFirst()
                .orElse(null);
    }

    public boolean hasElement(String elementName) {
        return findElement(elementName) != null;
    }

    public int getElementCount() {
        return elements.size();
    }

    /**
     * Convert to MetaFile (drops values)
     */
    public MetaFile toMetaFile() {
        List<MetaElement> metaElements = new ArrayList<>();
        for (ConfigElement element : elements) {
            metaElements.add(element.toMetaElement());
        }
        return new MetaFile(domainName, domainType, metaElements);
    }

    @Override
    public String toString() {
        return "DomainConfig{" +
                "lob='" + lob + '\'' +
                ", domainName='" + domainName + '\'' +
                ", domainType='" + domainType + '\'' +
                ", env='" + env + '\'' +
                ", action=" + action +
                ", elementCount=" + getElementCount() +
                '}';
    }
}
