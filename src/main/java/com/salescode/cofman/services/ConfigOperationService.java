package com.salescode.cofman.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.exception.ConfigNotFoundException;
import com.salescode.cofman.exception.ConfigOperationException;
import com.salescode.cofman.model.Merge;
import com.salescode.cofman.model.dto.*;
import com.salescode.cofman.model.enums.Action;
import com.salescode.cofman.model.enums.ElementPattern;
import com.salescode.cofman.repository.MergeRepository;
import com.salescode.cofman.util.ElementParser;
import com.salescode.cofman.util.FileNameSanitizer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Operation Service - High-level operations on configurations.
 *
 * Consolidates:
 * - ConfigService (CRUD operations)
 * - ConfigCopyService (100%)
 * - MergeService (100%)
 * - CSVImportService (100%)
 *
 * NO LOGIC CHANGES - Pure extraction
 */
@Slf4j
@Getter
@Service
public class ConfigOperationService {

    private static final String DEFAULT_LOB = "default";

    @Value("${config.base-path:src/main/resources/configs}")
    private String basePath;

    @Value("${config.default-env:ALL}")
    private String defaultEnv;

    private ConfigRepositoryService repository;
    private ConfigTransformService transform;

    @Autowired(required = false)
    private MergeRepository mergeRepository;

    @PostConstruct
    public void init() {
        this.repository = new ConfigRepositoryService(basePath);
        this.transform = new ConfigTransformService(repository, DEFAULT_LOB, defaultEnv);
    }

    // ==================== CRUD OPERATIONS (From ConfigService - NO CHANGES) ====================

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
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);

        // Read existing meta
        MetaFile metaFile = repository.readMetaFile(basePath.toMetaPath());

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

        // Write element file(s) for all environments
        List<String> envsToWrite = getEnvsToWrite(defaultEnv);

        for (String targetEnv : envsToWrite) {
            ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType, finalName, targetEnv);
            repository.ensureDirectory(elementPath.toElementDir());
            JsonNode resolvedValue = transform.replaceDynamicValues(configElement.getValue(), targetEnv);
            repository.writeJsonNode(elementPath.toEnvFile(), resolvedValue);
        }

        // Update meta file
        metaFile.addElement(configElement.toMetaElement());
        repository.writeMetaFile(basePath.toMetaPath(), metaFile);

        return configElement;
    }
    /**
     * Helper to get environments to process based on env parameter
     */
    private List<String> getEnvsToWrite(String env) {
        if ("ALL".equalsIgnoreCase(env)) {
            return List.of("uat", "demo", "prod");
        }
        return List.of(env);
    }

    /**
     * Insert with explicit name (for adding from another LOB)
     */
    public ConfigElement insertWithName(String lob, String domainName, String domainType,
                                        String elementName, ElementPattern pattern, JsonNode value) {
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);

        // Ensure domain exists (create if not)
        if (!repository.fileExists(basePath.toMetaPath())) {
            MetaFile newMeta = new MetaFile(domainName, domainType);
            repository.writeMetaFile(basePath.toMetaPath(), newMeta);
        }

        MetaFile metaFile = repository.readMetaFile(basePath.toMetaPath());

        String sanitizedName = FileNameSanitizer.sanitize(elementName);
        String finalName = getUniqueElementName(metaFile, sanitizedName);

        ConfigElement configElement = new ConfigElement(finalName, pattern, null, value);

        // Write element file(s) for all environments
        List<String> envsToWrite = getEnvsToWrite(defaultEnv);

        for (String targetEnv : envsToWrite) {
            ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType, finalName, targetEnv);
            repository.ensureDirectory(elementPath.toElementDir());
            JsonNode resolvedValue = transform.replaceDynamicValues(value, targetEnv);
            repository.writeJsonNode(elementPath.toEnvFile(), resolvedValue);
        }

        // Update meta
        metaFile.addElement(configElement.toMetaElement());
        repository.writeMetaFile(basePath.toMetaPath(), metaFile);

        return configElement;
    }

    /**
     * Update an existing element's value
     */
    public void updateElement(String lob, String domainName, String domainType,
                              String elementName, JsonNode newValue) {
        // Update for all environments
        List<String> envsToWrite = getEnvsToWrite(defaultEnv);

        for (String targetEnv : envsToWrite) {
            ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType, elementName, targetEnv);

            if (!repository.fileExists(elementPath.toEnvFile())) {
                throw new ConfigNotFoundException(domainName, domainType, elementName);
            }

            JsonNode resolvedValue = transform.replaceDynamicValues(newValue, targetEnv);
            repository.writeJsonNode(elementPath.toEnvFile(), resolvedValue);
        }
    }

    /**
     * Update element with pattern change (updates both value and meta)
     */
    public void updateElement(String lob, String domainName, String domainType,
                              String elementName, ElementPattern newPattern, JsonNode newValue) {
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);

        MetaFile metaFile = repository.readMetaFile(basePath.toMetaPath());
        MetaElement metaElement = metaFile.findElement(elementName);

        if (metaElement == null) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }

        // Update meta
        metaElement.setPattern(newPattern);
        repository.writeMetaFile(basePath.toMetaPath(), metaFile);

        // Update value for all environments
        List<String> envsToWrite = getEnvsToWrite(defaultEnv);

        for (String targetEnv : envsToWrite) {
            ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType, elementName, targetEnv);
            JsonNode resolvedValue = transform.replaceDynamicValues(newValue, targetEnv);
            repository.writeJsonNode(elementPath.toEnvFile(), resolvedValue);
        }
    }

    /**
     * Delete an element
     */
    public void deleteElement(String lob, String domainName, String domainType, String elementName) {
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);

        MetaFile metaFile = repository.readMetaFile(basePath.toMetaPath());

        if (!metaFile.hasElement(elementName)) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }

        // Remove from meta
        metaFile.removeElement(elementName);
        repository.writeMetaFile(basePath.toMetaPath(), metaFile);

        // Delete element folder (contains all env files)
        ConfigPath elementPath = repository.buildPathWithElement(lob, domainName, domainType, elementName);
        repository.deleteDirectory(elementPath.toElementDir());
    }

    /**
     * Delete entire domain type
     */
    public void deleteDomainType(String lob, String domainName, String domainType) {
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);
        repository.deleteDirectory(basePath.toDomainTypeDir());
    }

    /**
     * Check if domain type exists
     */
    public boolean exists(String lob, String domainName, String domainType) {
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);
        return repository.fileExists(basePath.toMetaPath());
    }

    /**
     * Check if element exists
     */
    public boolean elementExists(String lob, String domainName, String domainType, String elementName) {
        if (!exists(lob, domainName, domainType)) {
            return false;
        }

        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);
        MetaFile metaFile = repository.readMetaFile(basePath.toMetaPath());
        return metaFile.hasElement(elementName);
    }

    // ==================== COPY OPERATIONS (From ConfigCopyService - NO CHANGES) ====================

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

        ConfigPath sourcePath = repository.buildPath(sourceLob, "", "");
        Path sourceLobDir = sourcePath.toDomainTypeDir().getParent().getParent();

        if (!repository.directoryExists(sourceLobDir)) {
            throw new ConfigNotFoundException("Source LOB not found: " + sourceLob);
        }

        // Iterate through domain names
        repository.listSubdirectories(sourceLobDir).forEach(domainNameDir -> {
            String domainName = domainNameDir.getFileName().toString();

            // Iterate through domain types
            repository.listSubdirectories(domainNameDir).forEach(domainTypeDir -> {
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

        ConfigPath sourcePath = repository.buildPath(sourceLob, domainName, "");
        Path domainNameDir = sourcePath.toDomainTypeDir().getParent();

        if (!repository.directoryExists(domainNameDir)) {
            throw new ConfigNotFoundException("Domain name not found: " + sourceLob + "/" + domainName);
        }

        repository.listSubdirectories(domainNameDir).forEach(domainTypeDir -> {
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
        ConfigPath sourcePath = repository.buildPath(sourceLob, domainName, domainType);

        if (!repository.fileExists(sourcePath.toMetaPath())) {
            throw new ConfigNotFoundException(domainName, domainType);
        }

        ConfigPath targetPath = repository.buildPath(targetLob, domainName, domainType);

        // Check if target exists
        if (repository.fileExists(targetPath.toMetaPath())) {
            if (!overwrite) {
                throw new ConfigOperationException("copyDomainType",
                        "Target already exists: " + targetLob + "/" + domainName + "/" + domainType +
                                ". Use overwrite=true to replace.");
            }
            // Delete existing
            repository.deleteDirectory(targetPath.toDomainTypeDir());
        }

        // Copy entire directory
        repository.copyDirectory(sourcePath.toDomainTypeDir(), targetPath.toDomainTypeDir());
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
        ConfigPath sourceBasePath = repository.buildPath(sourceLob, domainName, domainType);

        if (!repository.fileExists(sourceBasePath.toMetaPath())) {
            throw new ConfigNotFoundException(domainName, domainType);
        }

        MetaFile sourceMeta = repository.readMetaFile(sourceBasePath.toMetaPath());
        MetaElement sourceElement = sourceMeta.findElement(elementName);

        if (sourceElement == null) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }

        // Prepare target
        ConfigPath targetBasePath = repository.buildPath(targetLob, domainName, domainType);

        // Create target domain type if doesn't exist
        MetaFile targetMeta;
        if (!repository.fileExists(targetBasePath.toMetaPath())) {
            targetMeta = new MetaFile(domainName, domainType);
            repository.ensureDirectory(targetBasePath.toDomainTypeDir());
            repository.writeMetaFile(targetBasePath.toMetaPath(), targetMeta);
        } else {
            targetMeta = repository.readMetaFile(targetBasePath.toMetaPath());
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
            ConfigPath targetElementPath = repository.buildPathWithElement(targetLob, domainName,
                    domainType, elementName);
            repository.deleteDirectory(targetElementPath.toElementDir());
        }

        // Add element to target meta
        targetMeta.addElement(new MetaElement(
                sourceElement.getName(),
                sourceElement.getPattern(),
                sourceElement.getGroup()
        ));
        repository.writeMetaFile(targetBasePath.toMetaPath(), targetMeta);

        // Copy all environment files
        List<String> envs = List.of("uat", "demo", "prod");

        for (String env : envs) {
            ConfigPath sourceElementPath = repository.buildPathWithEnv(sourceLob, domainName, domainType,
                    elementName, env);

            if (repository.fileExists(sourceElementPath.toEnvFile())) {
                ConfigPath targetElementPath = repository.buildPathWithEnv(targetLob, domainName, domainType,
                        elementName, env);

                JsonNode sourceValue = repository.readJsonNode(sourceElementPath.toEnvFile());
                repository.writeJsonNode(targetElementPath.toEnvFile(), sourceValue);
            }
        }
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

    // ==================== MERGE OPERATIONS (From MergeService - NO CHANGES) ====================

    public void initiateMerge(String id) {
        Merge merge = mergeRepository.findById(id).orElseThrow(() ->
                new RuntimeException("Merge not found: " + id));

        if (merge.isApproved() && !merge.isMerged()) {
            List<DomainConfig> domainConfigs = merge.getDomainConfigs();
            List<String> errors = new ArrayList<>();

            // Sort: process default LOB first, DELETE actions last
            List<DomainConfig> sortedConfigs = sortConfigs(domainConfigs);

            // Validate before processing
            errors.addAll(validateConfigs(sortedConfigs));

            if (!errors.isEmpty()) {
                merge.setResponse("Validation failed: " + String.join("; ", errors));
                mergeRepository.save(merge);
                return;
            }

            // Process each config
            for (DomainConfig config : sortedConfigs) {
                try {
                    processConfig(config);
                } catch (Exception e) {
                    errors.add(buildKey(config) + ": " + e.getMessage());
                }
            }

            if (errors.isEmpty()) {
                merge.setMerged(true);
                merge.setResponse("Merge successful");
            } else {
                merge.setResponse("Merge completed with errors: " + String.join("; ", errors));
            }

            mergeRepository.save(merge);
        }
    }

    /**
     * Sort configs: default LOB first, DELETE actions last
     */
    private List<DomainConfig> sortConfigs(List<DomainConfig> configs) {
        return configs.stream()
                .sorted(Comparator
                        .comparing((DomainConfig c) -> !DEFAULT_LOB.equals(c.getLob()))
                        .thenComparing(c -> c.getAction() == Action.DELETE ? 1 : 0))
                .collect(Collectors.toList());
    }

    /**
     * Validate all configs before processing
     */
    private List<String> validateConfigs(List<DomainConfig> configs) {
        List<String> errors = new ArrayList<>();

        for (DomainConfig config : configs) {
            // Skip validation for default LOB - it can be created/updated freely
            if (DEFAULT_LOB.equals(config.getLob())) {
                continue;
            }

            // For non-default LOBs, check if default exists
            if (config.getAction() == Action.INSERT || config.getAction() == Action.UPDATE) {
                if (!isDefaultPresent(config.getDomainName(), config.getDomainType(), configs)) {
                    errors.add(String.format(
                            "Default LOB must exist before creating/updating '%s'. " +
                                    "Please create default LOB configuration for %s/%s first.",
                            buildKey(config),
                            config.getDomainName(),
                            config.getDomainType()
                    ));
                }

                // Validate env is provided for non-default LOBs on INSERT/UPDATE
                if (config.getEnv() == null || config.getEnv().isEmpty()) {
                    errors.add("Environment required for non-default LOB: " + buildKey(config));
                }
            }
        }

        return errors;
    }

    /**
     * Check if default LOB exists for given domain (in batch or on filesystem)
     */
    private boolean isDefaultPresent(String domainName, String domainType, List<DomainConfig> configs) {
        // Check if default is being inserted/updated in this batch
        boolean inBatch = configs.stream()
                .anyMatch(c -> DEFAULT_LOB.equals(c.getLob()) &&
                        c.getDomainName().equals(domainName) &&
                        c.getDomainType().equals(domainType) &&
                        c.getAction() != Action.DELETE);

        if (inBatch) {
            return true;
        }

        // Check filesystem - meta file existence indicates default LOB config exists
        ConfigPath path = repository.buildPath(DEFAULT_LOB, domainName, domainType);
        return repository.fileExists(path.toMetaPath());
    }

    /**
     * Process single config based on action
     */
    private void processConfig(DomainConfig config) {

        switch (config.getAction()) {
            case INSERT:
                insertConfig(config);
                break;
            case UPDATE:
                updateConfig(config);
                break;
            case DELETE:
                deleteConfig(config);
                break;
        }
    }

    /**
     * INSERT - create new config (fail if exists)
     */
    private void insertConfig(DomainConfig config) {
        ConfigPath configPath = repository.buildPath(config.getLob(), config.getDomainName(),
                config.getDomainType());

        if (repository.fileExists(configPath.toMetaPath())) {
            log.error("Config already exists, use UPDATE");
            return;
        }

        writeConfig(config);
    }

    /**
     * UPDATE - overwrite existing config
     */
    private void updateConfig(DomainConfig config) {
        ConfigPath configPath = repository.buildPath(config.getLob(), config.getDomainName(),
                config.getDomainType());

        if (repository.directoryExists(configPath.toDomainTypeDir())) {
            repository.deleteDirectory(configPath.toDomainTypeDir());
        }

        writeConfig(config);
    }

    /**
     * DELETE - remove config
     */
    private void deleteConfig(DomainConfig config) {
        String lob = config.getLob();
        String domainName = config.getDomainName();
        String domainType = config.getDomainType();
        String env = config.getEnv();

        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);

        if (!repository.directoryExists(configPath.toDomainTypeDir())) {
            throw new RuntimeException("Config not found: " + buildKey(config));
        }

        // If env is "ALL", delete entire domain type directory
        if ("ALL".equalsIgnoreCase(env)) {
            repository.deleteDirectory(configPath.toDomainTypeDir());
            return;
        }

        // For specific env, delete only that env's files
        MetaFile metaFile = repository.readMetaFile(configPath.toMetaPath());

        if (metaFile.getElements() != null) {
            for (MetaElement element : metaFile.getElements()) {
                ConfigPath elementPath = repository.buildPathWithEnv(
                        lob, domainName, domainType,
                        element.getName(), env
                );

                // Delete the specific env file
                if (repository.fileExists(elementPath.toEnvFile())) {
                    repository.deleteFile(elementPath.toEnvFile());
                }
            }
        }
    }

    /**
     * Write config to filesystem
     */
    private void writeConfig(DomainConfig config) {
        String lob = config.getLob();
        String domainName = config.getDomainName();
        String domainType = config.getDomainType();
        String env = config.getEnv();

        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);

        // Create directory
        repository.ensureDirectory(configPath.toDomainTypeDir());

        // Decide envs to write using helper method
        List<String> envsToWrite = getEnvsToWrite(env);

        // Write each element for each env
        if (config.getElements() != null) {
            for (ConfigElement element : config.getElements()) {
                for (String targetEnv : envsToWrite) {
                    ConfigPath elementPath = repository.buildPathWithEnv(
                            lob, domainName, domainType,
                            element.getName(), targetEnv
                    );
                    repository.ensureDirectory(elementPath.toElementDir());
                    JsonNode resolvedValue =
                            transform.replaceDynamicValues(element.getValue(), targetEnv);

                    repository.writeJsonNode(elementPath.toEnvFile(), resolvedValue);
                }
            }
        }

        // Write _meta.json only once
        MetaFile metaFile = config.toMetaFile();
        repository.writeMetaFile(configPath.toMetaPath(), metaFile);
    }


    private String buildKey(DomainConfig config) {
        return config.getLob() + "/" + config.getDomainName() + "/" + config.getDomainType();
    }

    public List<ReconstructResult> getReconstructedConfig(String lob, String name, String type) {
        return transform.reconstruct(lob, name, type);
    }

    // ==================== CSV IMPORT (From CSVImportService - NO CHANGES) ====================

    /**
     * Import CSV file to config folder structure.
     * @param csvPath path to CSV file (filename: {lob}_{env}.csv)
     */
    public void importCsv(String csvPath) {
        Path path = Paths.get(csvPath);
        String fileName = path.getFileName().toString();

        // Parse lob_env from filename
        String nameWithoutExt = fileName.replace(".csv", "");
        int lastUnderscore = nameWithoutExt.lastIndexOf('_');
        String lob = nameWithoutExt.substring(0, lastUnderscore);
        String env = nameWithoutExt.substring(lastUnderscore + 1);

        log.info("Importing: lob={}, env={}", lob, env);

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            reader.readLine(); // skip header

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] values = parseCsvLine(line);
                String domainName = values[0].trim();
                String domainType = values[1].trim();
                String domainValues = values[2].trim();

                try {
                    JsonNode jsonArray = repository.getObjectMapper().readTree(domainValues);

                    // Check if meta already exists - if so, just add env files
                    ConfigPath metaPath = repository.buildPath(lob, domainName, domainType);

                    if (repository.fileExists(metaPath.toMetaPath())) {
                        // Domain exists - add env files only
                        addEnvFiles(lob, env, domainName, domainType, jsonArray);
                        log.info("Added env files: {}/{} ({})", domainName, domainType, env);
                    } else {
                        // New domain - use deconstruct
                        transform.deconstruct(lob, domainName, domainType, jsonArray,env);
                        log.info("Created: {}/{}", domainName, domainType);
                    }
                } catch (Exception e) {
                    log.error("Failed: {}/{} - {}", domainName, domainType, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to read CSV: {}", e.getMessage(), e);
        }
    }

    /**
     * Add env files to existing domain structure.
     */
    private void addEnvFiles(String lob, String env, String domainName, String domainType, JsonNode jsonArray) {
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);
        MetaFile metaFile = repository.readMetaFile(basePath.toMetaPath());

        int metaIndex = 0;

        for (JsonNode element : jsonArray) {
            if (metaIndex >= metaFile.getElements().size()) break;

            MetaElement currentMeta = metaFile.getElements().get(metaIndex);

            // Check if this is a multi-key pattern
            if (currentMeta.getPattern() == ElementPattern.MULTI_KEY_EXPLODE && element.isObject()) {
                // Process each key in the object
                Iterator<Map.Entry<String, JsonNode>> fields = element.fields();

                while (fields.hasNext() && metaIndex < metaFile.getElements().size()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    MetaElement metaElement = metaFile.getElements().get(metaIndex);

                    // Write the individual key's value
                    ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType,
                            metaElement.getName(), env);
                    Path envFile = elementPath.toEnvFile();

                    if (!repository.fileExists(envFile)) {
                        JsonNode resolvedValue = transform.replaceDynamicValues(field.getValue(), env);
                        repository.writeJsonNode(envFile, resolvedValue);
                    }

                    metaIndex++;
                }
            } else {
                // Single element - write as-is
                ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType,
                        currentMeta.getName(), env);
                Path envFile = elementPath.toEnvFile();

                if (!repository.fileExists(envFile)) {
                    JsonNode resolvedValue = transform.replaceDynamicValues(element, env);
                    repository.writeJsonNode(envFile, resolvedValue);
                }

                metaIndex++;
            }
        }
    }

    /**
     * Parse CSV line handling quoted values.
     */
    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    // ==================== HELPER METHODS ====================

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