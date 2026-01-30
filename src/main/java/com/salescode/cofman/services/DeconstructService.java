package com.salescode.cofman.services;

import com.salescode.cofman.exception.ConfigOperationException;
import com.salescode.cofman.model.dto.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.model.enums.Action;
import com.salescode.cofman.model.enums.ElementPattern;
import com.salescode.cofman.util.ElementParser;
import com.salescode.cofman.util.FileNameSanitizer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service for deconstructing JSON arrays into folder structures.
 * Converts database/CSV format to filesystem representation.
 */
@Slf4j
@Getter
public class DeconstructService {
    
    private final ConfigFileService fileService;
    private final String defaultLob;
    private final String defaultEnv;
    
    public DeconstructService(ConfigFileService fileService) {
        this(fileService, "default", "ALL");
    }
    
    public DeconstructService(ConfigFileService fileService, String defaultLob, String defaultEnv) {
        this.fileService = fileService;
        this.defaultLob = defaultLob;
        this.defaultEnv = defaultEnv;
    }
    
    /**
     * Deconstruct a JSON array into folder structure.
     * 
     * @param lob Line of Business
     * @param domainName Domain name
     * @param domainType Domain type
     * @param jsonArray The JSON array to deconstruct (domain_values)
     * @return DomainConfig representing what was created
     */
    public DomainConfig deconstruct(String lob, String domainName, String domainType, JsonNode jsonArray) {
        if (!jsonArray.isArray()) {
            throw new ConfigOperationException("deconstruct", 
                    "Expected JSON array, got: " + jsonArray.getNodeType());
        }
        
        String sanitizedDomainName = FileNameSanitizer.sanitize(domainName);
        String sanitizedDomainType = FileNameSanitizer.sanitize(domainType);
        
        DomainConfig domainConfig = new DomainConfig(lob, sanitizedDomainName, sanitizedDomainType, Action.INSERT, defaultEnv,new ArrayList<>());
        
        ConfigPath basePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(lob)
                .domainName(sanitizedDomainName)
                .domainType(sanitizedDomainType)
                .build();
        
        // Track name occurrences for duplicate handling
        Map<String, Integer> nameCounter = new HashMap<>();
        int fallbackIndex = 0;
        int groupCounter = 0;
        
        for (JsonNode element : jsonArray) {
            // Generate group ID for potential MULTI_KEY_EXPLODE
            String groupId = "group_" + groupCounter++;
            
            List<ConfigElement> parsedElements = ElementParser.parseElement(element, fallbackIndex, groupId);
            
            for (ConfigElement configElement : parsedElements) {
                String baseName = configElement.getName();
                
                // Handle duplicate names
                int count = nameCounter.getOrDefault(baseName, 0);
                String finalName = count == 0 ? baseName : baseName + "_" + count;
                nameCounter.put(baseName, count + 1);
                
                configElement.setName(finalName);
                
                // Update fallback index if using fallback pattern
                if (configElement.getPattern() == ElementPattern.FALLBACK) {
                    fallbackIndex++;
                }
                
                // Write element JSON file
                ConfigPath elementPath = ConfigPath.builder()
                        .basePath(fileService.getBasePath())
                        .lob(lob)
                        .domainName(sanitizedDomainName)
                        .domainType(sanitizedDomainType)
                        .elementName(finalName)
                        .env(defaultEnv)
                        .build();
                
                fileService.writeJsonNode(elementPath.toEnvFile(), configElement.getValue());
                
                domainConfig.addElement(configElement);
            }
        }
        
        // Write _meta.json
        MetaFile metaFile = domainConfig.toMetaFile();
        fileService.writeMetaFile(basePath.toMetaPath(), metaFile);
        
        return domainConfig;
    }
    
    /**
     * Deconstruct with default LOB
     */
    public DomainConfig deconstruct(String domainName, String domainType, JsonNode jsonArray) {
        return deconstruct(defaultLob, domainName, domainType, jsonArray);
    }
    
    /**
     * Deconstruct from a raw JSON string
     */
    public DomainConfig deconstructFromString(String lob, String domainName, String domainType, 
                                               String jsonArrayString) {
        try {
            ObjectMapper mapper = fileService.getObjectMapper();
            JsonNode jsonArray = mapper.readTree(jsonArrayString);
            return deconstruct(lob, domainName, domainType, jsonArray);
        } catch (Exception e) {
            throw new ConfigOperationException("deconstructFromString",
                    "Failed to parse JSON string", e);
        }
    }
    
    /**
     * Deconstruct from CSV-style input (domain_name, domain_type, domain_values columns)
     */
    public List<DomainConfig> deconstructFromCsvData(String lob, List<Map<String, String>> rows) {
        List<DomainConfig> results = new ArrayList<>();
        ObjectMapper mapper = fileService.getObjectMapper();
        
        for (Map<String, String> row : rows) {
            String domainName = row.get("domain_name");
            String domainType = row.get("domain_type");
            String domainValues = row.get("domain_values");
            
            if (domainName == null || domainType == null || domainValues == null) {
                log.warn("Skipping row with missing fields: {}", row);
                continue;
            }
            
            try {
                JsonNode jsonArray = mapper.readTree(domainValues);
                DomainConfig config = deconstruct(lob, domainName, domainType, jsonArray);
                results.add(config);
                log.info("Deconstructed: {}/{}", domainName, domainType);
            } catch (Exception e) {
                log.error("Failed to deconstruct {}/{}: {}", domainName, domainType, e.getMessage(), e);
            }
        }
        
        return results;
    }

}
