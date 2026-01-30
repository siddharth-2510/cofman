package com.salescode.cofman.services;

import com.salescode.cofman.model.dto.*;
import com.salescode.cofman.model.enums.ElementPattern;
import com.salescode.cofman.exception.ConfigNotFoundException;
import com.salescode.cofman.exception.ConfigOperationException;
import com.salescode.cofman.util.ElementParser;
import com.salescode.cofman.util.FileNameSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.List;

/**
 * Main service for configuration management.
 * Provides CRUD operations for config elements.
 */
@Getter
public class ConfigService {
    
    private final ConfigFileService fileService;
    private final DeconstructService deconstructService;
    private final ReconstructService reconstructService;
    private final String defaultEnv;
    
    public ConfigService(String basePath) {
        this(basePath, "default", "ALL");
    }
    
    public ConfigService(String basePath, String defaultLob, String defaultEnv) {
        this.fileService = new ConfigFileService(basePath);
        this.deconstructService = new DeconstructService(fileService, defaultLob, defaultEnv);
        this.reconstructService = new ReconstructService(fileService, defaultEnv);
        this.defaultEnv = defaultEnv;
    }
    
    // ==================== DECONSTRUCT ====================
    
    /**
     * Deconstruct JSON array to folder structure
     */
    public DomainConfig deconstruct(String lob, String domainName, String domainType, JsonNode jsonArray) {
        return deconstructService.deconstruct(lob, domainName, domainType, jsonArray);
    }
    
    /**
     * Deconstruct from JSON string
     */
    public DomainConfig deconstructFromString(String lob, String domainName, String domainType, 
                                               String jsonArrayString) {
        return deconstructService.deconstructFromString(lob, domainName, domainType, jsonArrayString);
    }
    
    // ==================== RECONSTRUCT ====================
    
    /**
     * Reconstruct entire domain type to JSON array
     */
    public List<ReconstructResult> reconstruct(String lob, String domainName, String domainType) {
        return reconstructService.reconstruct(lob, domainName, domainType);
    }
    
    /**
     * Reconstruct with specific environment
     */
    public ReconstructResult reconstruct(String lob, String domainName, String domainType, String env) {
        return reconstructService.reconstruct(lob, domainName, domainType, env);
    }
    
    /**
     * Reconstruct specific element
     */
    public JsonNode reconstructElement(String lob, String domainName, String domainType, String elementName) {
        return reconstructService.reconstructElement(lob, domainName, domainType, elementName);
    }
    
    // ==================== INSERT ====================
    
    /**
     * Insert a new element to existing domain.
     * Element is added at the end of the order.
     * 
     * @param lob Line of Business
     * @param domainName Domain name
     * @param domainType Domain type
     * @param element The JSON element to insert
     * @return The created ConfigElement
     */
    public ConfigElement insert(String lob, String domainName, String domainType, JsonNode element) {
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        // Read existing meta
        MetaFile metaFile = fileService.readMetaFile(basePath.toMetaPath());
        
        // Parse the element
        int fallbackIndex = countFallbackElements(metaFile);
        List<ConfigElement> parsed = ElementParser.parseElement(element, fallbackIndex);
        
        if (parsed.isEmpty()) {
            throw new ConfigOperationException("insert", "Failed to parse element");
        }
        
        // For now, only support inserting single elements (not MULTI_KEY_EXPLODE)
        if (parsed.size() > 1) {
            throw new ConfigOperationException("insert", 
                    "Multi-key objects must be inserted as individual elements or use deconstruct");
        }
        
        ConfigElement configElement = parsed.get(0);
        
        // Check for duplicate name
        String finalName = getUniqueElementName(metaFile, configElement.getName());
        configElement.setName(finalName);
        
        // Write element file
        ConfigPath elementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(finalName)
                .env(defaultEnv)
                .build();
        
        fileService.writeJsonNode(elementPath.toEnvFile(), configElement.getValue());
        
        // Update meta file
        metaFile.addElement(configElement.toMetaElement());
        fileService.writeMetaFile(basePath.toMetaPath(), metaFile);
        
        return configElement;
    }
    
    /**
     * Insert with explicit name (for adding from another LOB)
     */
    public ConfigElement insertWithName(String lob, String domainName, String domainType, 
                                         String elementName, ElementPattern pattern, JsonNode value) {
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        // Ensure domain exists (create if not)
        if (!fileService.fileExists(basePath.toMetaPath())) {
            MetaFile newMeta = new MetaFile(domainName, domainType);
            fileService.writeMetaFile(basePath.toMetaPath(), newMeta);
        }
        
        MetaFile metaFile = fileService.readMetaFile(basePath.toMetaPath());
        
        String sanitizedName = FileNameSanitizer.sanitize(elementName);
        String finalName = getUniqueElementName(metaFile, sanitizedName);
        
        ConfigElement configElement = new ConfigElement(finalName, pattern, null, value);
        
        // Write element file
        ConfigPath elementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(finalName)
                .env(defaultEnv)
                .build();
        
        fileService.writeJsonNode(elementPath.toEnvFile(), value);
        
        // Update meta
        metaFile.addElement(configElement.toMetaElement());
        fileService.writeMetaFile(basePath.toMetaPath(), metaFile);
        
        return configElement;
    }
    
    // ==================== UPDATE ====================
    
    /**
     * Update an existing element's value
     */
    public void updateElement(String lob, String domainName, String domainType, 
                              String elementName, JsonNode newValue) {
        ConfigPath elementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(elementName)
                .env(defaultEnv)
                .build();
        
        if (!fileService.fileExists(elementPath.toEnvFile())) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }
        
        fileService.writeJsonNode(elementPath.toEnvFile(), newValue);
    }
    
    /**
     * Update element with pattern change (updates both value and meta)
     */
    public void updateElement(String lob, String domainName, String domainType, 
                              String elementName, ElementPattern newPattern, JsonNode newValue) {
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        MetaFile metaFile = fileService.readMetaFile(basePath.toMetaPath());
        MetaElement metaElement = metaFile.findElement(elementName);
        
        if (metaElement == null) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }
        
        // Update meta
        metaElement.setPattern(newPattern);
        fileService.writeMetaFile(basePath.toMetaPath(), metaFile);
        
        // Update value
        ConfigPath elementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(elementName)
                .env(defaultEnv)
                .build();
        
        fileService.writeJsonNode(elementPath.toEnvFile(), newValue);
    }
    
    // ==================== DELETE ====================
    
    /**
     * Delete an element
     */
    public void deleteElement(String lob, String domainName, String domainType, String elementName) {
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        MetaFile metaFile = fileService.readMetaFile(basePath.toMetaPath());
        
        if (!metaFile.hasElement(elementName)) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }
        
        // Remove from meta
        metaFile.removeElement(elementName);
        fileService.writeMetaFile(basePath.toMetaPath(), metaFile);
        
        // Delete element folder
        ConfigPath elementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(elementName)
                .build();
        
        fileService.deleteDirectory(elementPath.toElementDir());
    }
    
    /**
     * Delete entire domain type
     */
    public void deleteDomainType(String lob, String domainName, String domainType) {
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        fileService.deleteDirectory(basePath.toDomainTypeDir());
    }
    
    // ==================== VALIDATION ====================
    
    /**
     * Validate a domain configuration
     */
    public List<String> validate(String lob, String domainName, String domainType) {
        return reconstructService.validate(lob, domainName, domainType);
    }
    
    /**
     * Check if domain type exists
     */
    public boolean exists(String lob, String domainName, String domainType) {
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        return fileService.fileExists(basePath.toMetaPath());
    }
    
    /**
     * Check if element exists
     */
    public boolean elementExists(String lob, String domainName, String domainType, String elementName) {
        if (!exists(lob, domainName, domainType)) {
            return false;
        }
        
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        MetaFile metaFile = fileService.readMetaFile(basePath.toMetaPath());
        return metaFile.hasElement(elementName);
    }
    
    // ==================== HELPERS ====================
    
    private int countFallbackElements(MetaFile metaFile) {
        int count = 0;
        for (MetaElement element : metaFile.getElements()) {
            if (element.getName().startsWith("item_")) {
                count++;
            }
        }
        return count;
    }
    
    private String getUniqueElementName(MetaFile metaFile, String baseName) {
        if (!metaFile.hasElement(baseName)) {
            return baseName;
        }
        
        int counter = 1;
        String newName;
        do {
            newName = baseName + "_" + counter++;
        } while (metaFile.hasElement(newName));
        
        return newName;
    }
}
