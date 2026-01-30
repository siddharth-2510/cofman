package com.salescode.cofman.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Result object returned by reconstruction operations.
 * Contains the reconstructed JSON array along with metadata and any warnings.
 */
@Getter
@Setter
public class ReconstructResult {
    
    private String lob;
    private String domainName;
    private String domainType;
    private String env;
    private JsonNode jsonArray;
    private int elementCount;
    private List<String> warnings;
    private boolean success;
    private String errorMessage;
    
    public ReconstructResult() {
        this.warnings = new ArrayList<>();
        this.success = true;
    }
    
    public static ReconstructResult success(String lob, String domainName, String domainType, 
                                            String env, JsonNode jsonArray, int elementCount) {
        ReconstructResult result = new ReconstructResult();
        result.lob = lob;
        result.domainName = domainName;
        result.domainType = domainType;
        result.env = env;
        result.jsonArray = jsonArray;
        result.elementCount = elementCount;
        result.success = true;
        return result;
    }
    
    public static ReconstructResult failure(String domainName, String domainType, String errorMessage) {
        ReconstructResult result = new ReconstructResult();
        result.domainName = domainName;
        result.domainType = domainType;
        result.success = false;
        result.errorMessage = errorMessage;
        return result;
    }
    
    public void addWarning(String warning) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        this.warnings.add(warning);
    }
    
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ReconstructResult{" +
                "lob='" + lob + '\'' +
                ", domainName='" + domainName + '\'' +
                ", domainType='" + domainType + '\'' +
                ", env='" + env + '\'' +
                ", elementCount=" + elementCount +
                ", success=" + success +
                ", warnings=" + warnings +
                (errorMessage != null ? ", errorMessage='" + errorMessage + '\'' : "") +
                '}';
    }
}
