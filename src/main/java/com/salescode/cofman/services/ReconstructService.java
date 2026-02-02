package com.salescode.cofman.services;

import com.salescode.cofman.model.dto.*;
import com.salescode.cofman.exception.ConfigNotFoundException;
import com.salescode.cofman.exception.InvalidMetaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salescode.cofman.model.enums.ElementPattern;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

/**
 * Service for reconstructing JSON arrays from folder structures.
 * Converts filesystem representation back to database/API format.
 */
public class ReconstructService {

    private static final List<String> SUPPORTED_ENVS =
            List.of("ALL", "UAT", "DEV", "DEMO", "PROD");
    private final ConfigFileService fileService;
    private final ObjectMapper objectMapper;
    private final String defaultEnv;
    
    public ReconstructService(ConfigFileService fileService) {
        this(fileService, "ALL");
    }
    
    public ReconstructService(ConfigFileService fileService, String defaultEnv) {
        this.fileService = fileService;
        this.objectMapper = fileService.getObjectMapper();
        this.defaultEnv = defaultEnv;
    }
    
    /**
     * Reconstruct entire domain type to JSON array.
     * 
     * @param lob Line of Business
     * @param domainName Domain name
     * @param domainType Domain type
     * @return ReconstructResult containing the JSON array and metadata
     */

    public List<ReconstructResult> reconstruct(
            String lob,
            String domainName,
            String domainType) {

        List<ReconstructResult> results = new ArrayList<>();

        for (String env : SUPPORTED_ENVS) {
            try {
                ReconstructResult envResult =
                        reconstruct(lob, domainName, domainType, env);

                results.add(envResult);

            } catch (ConfigNotFoundException ex) {
                // env config not present → skip, but don’t fail
                ReconstructResult warningResult = new ReconstructResult();
                warningResult.setLob(lob);
                warningResult.setDomainName(domainName);
                warningResult.setDomainType(domainType);
                warningResult.setEnv(env);
                warningResult.setSuccess(false);
                warningResult.addWarning("Config not found for env: " + env);

                results.add(warningResult);
            }
        }

        return results;
    }


    /**
     * Reconstruct with specific environment
     */
    public ReconstructResult reconstruct(String lob, String domainName, String domainType, String env) {
        ConfigPath configPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .env(env)
                .build();
        
        Path metaPath = configPath.toMetaPath();
        
        if (!fileService.fileExists(metaPath)) {
            throw new ConfigNotFoundException(domainName, domainType);
        }
        
        MetaFile metaFile = fileService.readMetaFile(metaPath);
        ReconstructResult result = new ReconstructResult();
        result.setLob(lob);
        result.setDomainName(domainName);
        result.setDomainType(domainType);
        result.setEnv(env);
        
        ArrayNode jsonArray = objectMapper.createArrayNode();
        List<ConfigElement> elements = new ArrayList<>();
        
        // Load all elements with their values
        for (MetaElement metaElement : metaFile.getElements()) {
            ConfigPath elementPath = ConfigPath.builder()
                    .basePath(fileService.getBasePath())
                    .lob(lob)
                    .domainName(domainName)
                    .domainType(domainType)
                    .elementName(metaElement.getName())
                    .env(env)
                    .build();
            
            Path envFilePath = elementPath.toEnvFile();
            
            if (!fileService.fileExists(envFilePath)) {
                result.addWarning("Element folder missing: " + metaElement.getName());
                continue;
            }
            
            JsonNode value = fileService.readJsonNode(envFilePath);
            ConfigElement configElement = ConfigElement.fromMeta(metaElement, value);
            elements.add(configElement);
        }
        
        // Check for orphan folders (exist on disk but not in _meta.json)
        checkOrphanFolders(configPath.toDomainTypeDir(), metaFile, result);
        
        // Reconstruct JSON array respecting patterns and groups
        reconstructJsonArray(elements, jsonArray);
        
        result.setJsonArray(jsonArray);
        result.setElementCount(jsonArray.size());
        result.setSuccess(true);
        
        return result;
    }
    
    /**
     * Reconstruct a specific element by name.
     * Returns single element value (not wrapped in array).
     * 
     * @param lob Line of Business
     * @param domainName Domain name
     * @param domainType Domain type
     * @param elementName Name of the element
     * @return The element's JSON value
     */
    public JsonNode reconstructElement(String lob, String domainName, String domainType, String elementName) {
        return reconstructElement(lob, domainName, domainType, elementName, defaultEnv);
    }
    
    /**
     * Reconstruct a specific element with specific environment
     */
    public JsonNode reconstructElement(String lob, String domainName, String domainType, 
                                        String elementName, String env) {
        ConfigPath configPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(elementName)
                .env(env)
                .build();
        
        // First read meta to get pattern info
        Path metaPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build()
                .toMetaPath();
        
        if (!fileService.fileExists(metaPath)) {
            throw new ConfigNotFoundException(domainName, domainType);
        }
        
        MetaFile metaFile = fileService.readMetaFile(metaPath);
        MetaElement metaElement = metaFile.findElement(elementName);
        
        if (metaElement == null) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }
        
        Path envFilePath = configPath.toEnvFile();
        
        if (!fileService.fileExists(envFilePath)) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }
        
        JsonNode value = fileService.readJsonNode(envFilePath);
        
        // Reconstruct based on pattern
        return reconstructSingleElement(metaElement, value);
    }
    
    /**
     * Reconstruct JSON array from elements, respecting patterns and groups
     */
    private void reconstructJsonArray(List<ConfigElement> elements, ArrayNode jsonArray) {
        String currentGroup = null;
        ObjectNode currentGroupObject = null;
        
        for (ConfigElement element : elements) {
            ElementPattern pattern = element.getPattern();
            String group = element.getGroup();
            JsonNode value = element.getValue();
            
            // Handle MULTI_KEY_EXPLODE grouping
            if (pattern == ElementPattern.MULTI_KEY_EXPLODE && group != null) {
                if (!group.equals(currentGroup)) {
                    // New group - save previous if exists
                    if (currentGroupObject != null) {
                        jsonArray.add(currentGroupObject);
                    }
                    currentGroupObject = objectMapper.createObjectNode();
                    currentGroup = group;
                }
                // Add to current group object
                currentGroupObject.set(element.getName(), value);
            } else {
                // Non-grouped element - save any pending group first
                if (currentGroupObject != null) {
                    jsonArray.add(currentGroupObject);
                    currentGroupObject = null;
                    currentGroup = null;
                }
                
                // Add element based on its pattern
                JsonNode reconstructed = reconstructSingleElement(element.toMetaElement(), value);
                jsonArray.add(reconstructed);
            }
        }
        
        // Don't forget the last group
        if (currentGroupObject != null) {
            jsonArray.add(currentGroupObject);
        }
    }
    
    /**
     * Reconstruct a single element based on its pattern
     */
    private JsonNode reconstructSingleElement(MetaElement metaElement, JsonNode value) {
        switch (metaElement.getPattern()) {
            case SINGLE_KEY_OBJECT:
                // Wrap value back in single-key object
                ObjectNode wrapper = objectMapper.createObjectNode();
                wrapper.set(metaElement.getName(), value);
                return wrapper;
                
            case NAME_FIELD:
            case TYPE_FIELD:
            case ID_FIELD:
            case PLAIN_STRING:
            case PRIMITIVE:
            case FALLBACK:
            default:
                // Return as-is
                return value;
        }
    }
    
    /**
     * Check for orphan folders (exist on disk but not in _meta.json)
     */
    private void checkOrphanFolders(Path domainTypeDir, MetaFile metaFile, ReconstructResult result) {
        Set<String> metaElementNames = new HashSet<>();
        for (MetaElement element : metaFile.getElements()) {
            metaElementNames.add(element.getName());
        }
        
        fileService.listSubdirectories(domainTypeDir).forEach(subDir -> {
            String dirName = subDir.getFileName().toString();
            if (!dirName.startsWith("_") && !metaElementNames.contains(dirName)) {
                result.addWarning("Orphan folder found: " + dirName);
            }
        });
    }
    
    /**
     * Validate a domain configuration without reconstructing
     */
    public List<String> validate(String lob, String domainName, String domainType) {
        List<String> errors = new ArrayList<>();
        
        ConfigPath configPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        Path metaPath = configPath.toMetaPath();
        
        if (!fileService.fileExists(metaPath)) {
            errors.add("Meta file not found: " + metaPath);
            return errors;
        }
        
        try {
            MetaFile metaFile = fileService.readMetaFile(metaPath);
            
            // Check each element exists
            for (MetaElement element : metaFile.getElements()) {
                ConfigPath elementPath = ConfigPath.builder()
                        .basePath(fileService.getBasePath())
                        .lob(lob)
                        .domainName(domainName)
                        .domainType(domainType)
                        .elementName(element.getName())
                        .env(defaultEnv)
                        .build();
                
                if (!fileService.fileExists(elementPath.toEnvFile())) {
                    errors.add("Element file missing: " + element.getName() + "/" + defaultEnv + ".json");
                }
            }
            
            // Check for duplicates in meta
            Set<String> seen = new HashSet<>();
            for (MetaElement element : metaFile.getElements()) {
                if (!seen.add(element.getName())) {
                    errors.add("Duplicate element in meta: " + element.getName());
                }
            }
            
        } catch (Exception e) {
            errors.add("Failed to read meta file: " + e.getMessage());
        }
        
        return errors;
    }
}
