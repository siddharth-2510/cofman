package com.salescode.cofman.model.dto;

import lombok.Getter;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to build and parse filesystem paths consistently.
 * 
 * Path structure:
 * {basePath}/{lob}/{domainName}/{domainType}/{elementName}/{env}.json
 * 
 * Example:
 * configs/default/payment_methods/cards/visa/ALL.json
 */
@Getter
public class ConfigPath {
    
    private static final String META_FILE_NAME = "_meta.json";
    private static final String DEFAULT_ENV = "ALL";
    private static final String JSON_EXTENSION = ".json";
    
    private String basePath;
    private String lob;
    private String domainName;
    private String domainType;
    private String elementName;
    private String env;
    
    public ConfigPath() {
        this.env = DEFAULT_ENV;
    }
    
    private ConfigPath(Builder builder) {
        this.basePath = builder.basePath;
        this.lob = builder.lob;
        this.domainName = builder.domainName;
        this.domainType = builder.domainType;
        this.elementName = builder.elementName;
        this.env = builder.env != null ? builder.env : DEFAULT_ENV;
    }
    
    /**
     * Get path to the domain type directory
     * Example: configs/default/payment_methods/cards
     */
    public Path toDomainTypeDir() {
        return Paths.get(basePath, lob, domainName, domainType);
    }
    
    /**
     * Get path to _meta.json file
     * Example: configs/default/payment_methods/cards/_meta.json
     */
    public Path toMetaPath() {
        return toDomainTypeDir().resolve(META_FILE_NAME);
    }
    
    /**
     * Get path to element directory
     * Example: configs/default/payment_methods/cards/visa
     */
    public Path toElementDir() {
        if (elementName == null || elementName.isEmpty()) {
            throw new IllegalStateException("Element name is required for element directory path");
        }
        return toDomainTypeDir().resolve(elementName);
    }
    
    /**
     * Get path to environment-specific JSON file
     * Example: configs/default/payment_methods/cards/visa/ALL.json
     */
    public Path toEnvFile() {
        return toElementDir().resolve(env + JSON_EXTENSION);
    }
    
    /**
     * Get path to specific environment file for an element
     */
    public Path toEnvFile(String envName) {
        return toElementDir().resolve(envName + JSON_EXTENSION);
    }
    
    /**
     * Parse a path string back into ConfigPath components
     * Expected format: {basePath}/{lob}/{domainName}/{domainType}/[{elementName}/[{env}.json]]
     */
    public static ConfigPath parse(String pathStr, String basePath) {
        Path path = Paths.get(pathStr);
        Path base = Paths.get(basePath);
        
        // Make path relative to base
        if (path.startsWith(base)) {
            path = base.relativize(path);
        }
        
        int nameCount = path.getNameCount();
        
        Builder builder = builder().basePath(basePath);
        
        if (nameCount >= 1) {
            builder.lob(path.getName(0).toString());
        }
        if (nameCount >= 2) {
            builder.domainName(path.getName(1).toString());
        }
        if (nameCount >= 3) {
            builder.domainType(path.getName(2).toString());
        }
        if (nameCount >= 4) {
            String elementOrMeta = path.getName(3).toString();
            if (!elementOrMeta.equals(META_FILE_NAME)) {
                builder.elementName(elementOrMeta);
            }
        }
        if (nameCount >= 5) {
            String fileName = path.getName(4).toString();
            if (fileName.endsWith(JSON_EXTENSION)) {
                builder.env(fileName.substring(0, fileName.length() - JSON_EXTENSION.length()));
            }
        }
        
        return builder.build();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public String toString() {
        return "ConfigPath{" +
                "basePath='" + basePath + '\'' +
                ", lob='" + lob + '\'' +
                ", domainName='" + domainName + '\'' +
                ", domainType='" + domainType + '\'' +
                ", elementName='" + elementName + '\'' +
                ", env='" + env + '\'' +
                '}';
    }
    
    /**
     * Builder for ConfigPath
     */
    public static class Builder {
        private String basePath = "configs";
        private String lob = "default";
        private String domainName;
        private String domainType;
        private String elementName;
        private String env = DEFAULT_ENV;
        
        public Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }
        
        public Builder lob(String lob) {
            this.lob = lob;
            return this;
        }
        
        public Builder domainName(String domainName) {
            this.domainName = domainName;
            return this;
        }
        
        public Builder domainType(String domainType) {
            this.domainType = domainType;
            return this;
        }
        
        public Builder elementName(String elementName) {
            this.elementName = elementName;
            return this;
        }
        
        public Builder env(String env) {
            this.env = env;
            return this;
        }
        
        public ConfigPath build() {
            return new ConfigPath(this);
        }
    }
}
