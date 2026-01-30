package com.salescode.cofman.services;

import com.salescode.cofman.exception.ConfigNotFoundException;
import com.salescode.cofman.exception.ConfigOperationException;
import com.salescode.cofman.model.dto.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for copying configurations across LOBs.
 * Supports copying entire LOBs, domains, domain types, or individual elements.
 */
@Slf4j
public class ConfigCopyService {
    
    private final ConfigFileService fileService;
    private final String defaultEnv;
    
    public ConfigCopyService(ConfigService configService) {
        this(configService, "ALL");
    }
    
    public ConfigCopyService(ConfigService configService, String defaultEnv) {
        this.fileService = configService.getFileService();
        this.defaultEnv = defaultEnv;
    }
    
    /**
     * Copy entire LOB to another LOB.
     * 
     * @param sourceLob Source LOB
     * @param targetLob Target LOB
     * @param overwrite If true, overwrite existing elements
     * @return List of copied domain configs
     */
    public List<String> copyEntireLob(String sourceLob, String targetLob, boolean overwrite) {
        List<String> copied = new ArrayList<>();
        
        ConfigPath sourcePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(sourceLob)
                .build();
        
        Path sourceLobDir = sourcePath.toDomainTypeDir().getParent().getParent();
        
        if (!fileService.directoryExists(sourceLobDir)) {
            throw new ConfigNotFoundException("Source LOB not found: " + sourceLob);
        }
        
        // Iterate through domain names
        fileService.listSubdirectories(sourceLobDir).forEach(domainNameDir -> {
            String domainName = domainNameDir.getFileName().toString();
            
            // Iterate through domain types
            fileService.listSubdirectories(domainNameDir).forEach(domainTypeDir -> {
                String domainType = domainTypeDir.getFileName().toString();
                
                try {
                    copyDomainType(sourceLob, targetLob, domainName, domainType, overwrite);
                    copied.add(domainName + "/" + domainType);
                } catch (Exception e) {
                    log.error("Failed to copy {}/{}: {}", domainName, domainType, e.getMessage(), e);
                }
            });
        });
        
        return copied;
    }
    
    /**
     * Copy all domain types under a domain name.
     * 
     * @param sourceLob Source LOB
     * @param targetLob Target LOB
     * @param domainName Domain name to copy
     * @param overwrite If true, overwrite existing elements
     * @return List of copied domain types
     */
    public List<String> copyDomainName(String sourceLob, String targetLob, String domainName, boolean overwrite) {
        List<String> copied = new ArrayList<>();
        
        ConfigPath sourcePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(sourceLob)
                .domainName(domainName)
                .build();
        
        Path domainNameDir = sourcePath.toDomainTypeDir().getParent();
        
        if (!fileService.directoryExists(domainNameDir)) {
            throw new ConfigNotFoundException("Domain name not found: " + sourceLob + "/" + domainName);
        }
        
        fileService.listSubdirectories(domainNameDir).forEach(domainTypeDir -> {
            String domainType = domainTypeDir.getFileName().toString();
            
            try {
                copyDomainType(sourceLob, targetLob, domainName, domainType, overwrite);
                copied.add(domainType);
            } catch (Exception e) {
                log.error("Failed to copy {}: {}", domainType, e.getMessage(), e);
            }
        });
        
        return copied;
    }
    
    /**
     * Copy a specific domain type.
     * 
     * @param sourceLob Source LOB
     * @param targetLob Target LOB
     * @param domainName Domain name
     * @param domainType Domain type to copy
     * @param overwrite If true, overwrite existing elements
     */
    public void copyDomainType(String sourceLob, String targetLob, String domainName, 
                               String domainType, boolean overwrite) {
        ConfigPath sourcePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(sourceLob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        if (!fileService.fileExists(sourcePath.toMetaPath())) {
            throw new ConfigNotFoundException(domainName, domainType);
        }
        
        ConfigPath targetPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(targetLob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        // Check if target exists
        if (fileService.fileExists(targetPath.toMetaPath())) {
            if (!overwrite) {
                throw new ConfigOperationException("copyDomainType",
                        "Target already exists: " + targetLob + "/" + domainName + "/" + domainType + 
                        ". Use overwrite=true to replace.");
            }
            // Delete existing
            fileService.deleteDirectory(targetPath.toDomainTypeDir());
        }
        
        // Copy entire directory
        fileService.copyDirectory(sourcePath.toDomainTypeDir(), targetPath.toDomainTypeDir());
    }
    
    /**
     * Copy a specific element to another LOB.
     * Creates the domain type in target if it doesn't exist.
     * 
     * @param sourceLob Source LOB
     * @param targetLob Target LOB
     * @param domainName Domain name
     * @param domainType Domain type
     * @param elementName Element to copy
     * @param overwrite If true, overwrite existing element
     */
    public void copyElement(String sourceLob, String targetLob, String domainName, 
                            String domainType, String elementName, boolean overwrite) {
        // Read source element
        ConfigPath sourceBasePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(sourceLob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        if (!fileService.fileExists(sourceBasePath.toMetaPath())) {
            throw new ConfigNotFoundException(domainName, domainType);
        }
        
        MetaFile sourceMeta = fileService.readMetaFile(sourceBasePath.toMetaPath());
        MetaElement sourceElement = sourceMeta.findElement(elementName);
        
        if (sourceElement == null) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }
        
        // Read source value
        ConfigPath sourceElementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(sourceLob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(elementName)
                .env(defaultEnv)
                .build();
        
        com.fasterxml.jackson.databind.JsonNode sourceValue = 
                fileService.readJsonNode(sourceElementPath.toEnvFile());
        
        // Prepare target
        ConfigPath targetBasePath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(targetLob)
                .domainName(domainName)
                .domainType(domainType)
                .build();
        
        // Create target domain type if doesn't exist
        MetaFile targetMeta;
        if (!fileService.fileExists(targetBasePath.toMetaPath())) {
            targetMeta = new MetaFile(domainName, domainType);
            fileService.ensureDirectory(targetBasePath.toDomainTypeDir());
            fileService.writeMetaFile(targetBasePath.toMetaPath(), targetMeta);
        } else {
            targetMeta = fileService.readMetaFile(targetBasePath.toMetaPath());
        }
        
        // Check if element exists in target
        if (targetMeta.hasElement(elementName)) {
            if (!overwrite) {
                throw new ConfigOperationException("copyElement",
                        "Element already exists in target: " + elementName + 
                        ". Use overwrite=true to replace.");
            }
            // Remove existing
            targetMeta.removeElement(elementName);
            ConfigPath targetElementPath = ConfigPath.builder()
                    .basePath(fileService.getBasePath())
                    .lob(targetLob)
                    .domainName(domainName)
                    .domainType(domainType)
                    .elementName(elementName)
                    .build();
            fileService.deleteDirectory(targetElementPath.toElementDir());
        }
        
        // Add element to target meta
        targetMeta.addElement(new MetaElement(
                sourceElement.getName(),
                sourceElement.getPattern(),
                sourceElement.getGroup()
        ));
        fileService.writeMetaFile(targetBasePath.toMetaPath(), targetMeta);
        
        // Write element value
        ConfigPath targetElementPath = ConfigPath.builder()
                .basePath(fileService.getBasePath())
                .lob(targetLob)
                .domainName(domainName)
                .domainType(domainType)
                .elementName(elementName)
                .env(defaultEnv)
                .build();
        
        fileService.writeJsonNode(targetElementPath.toEnvFile(), sourceValue);
    }
    
    /**
     * Copy multiple specific elements to another LOB.
     * 
     * @param sourceLob Source LOB
     * @param targetLob Target LOB
     * @param domainName Domain name
     * @param domainType Domain type
     * @param elementNames List of elements to copy
     * @param overwrite If true, overwrite existing elements
     * @return List of successfully copied element names
     */
    public List<String> copyElements(String sourceLob, String targetLob, String domainName,
                                      String domainType, List<String> elementNames, boolean overwrite) {
        List<String> copied = new ArrayList<>();
        
        for (String elementName : elementNames) {
            try {
                copyElement(sourceLob, targetLob, domainName, domainType, elementName, overwrite);
                copied.add(elementName);
            } catch (Exception e) {
                log.error("Failed to copy element {}: {}", elementName, e.getMessage(), e);
            }
        }
        
        return copied;
    }
}
