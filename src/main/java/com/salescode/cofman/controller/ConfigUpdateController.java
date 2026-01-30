package com.salescode.cofman.controller;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.model.dto.ConfigElement;
import com.salescode.cofman.model.dto.ConfigPath;
import com.salescode.cofman.model.dto.DomainConfig;
import com.salescode.cofman.model.enums.Action;
import com.salescode.cofman.services.ConfigFileService;
import com.salescode.cofman.services.DeconstructService;
import com.salescode.cofman.services.MergeService;
import com.salescode.cofman.services.ReconstructService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Controller for direct config updates.
 * Accepts { "old": [...], "new": [...] } format where:
 * - "old" contains configs to be replaced/deleted
 * - "new" contains configs to be added/updated
 *
 * Logic:
 * - If only "new" present: INSERT new elements (merge with existing if config exists)
 * - If both "old" and "new" present: REPLACE old with new (full replacement)
 * - If only "old" present: DELETE the config
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin("*")
@Slf4j
public class ConfigUpdateController {

    @Value("${config.base-path:src/main/resources/configs}")
    private String basePath;

    private ConfigFileService fileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        this.fileService = new ConfigFileService(basePath);
    }

    /**
     * POST /api/config/update
     *
     * Request body: {
     *   "old": [ DomainConfigEntity, ... ],  // Configs to replace/delete
     *   "new": [ DomainConfigEntity, ... ]   // Configs to add/update
     * }
     *
     * Each DomainConfigEntity: {
     *   "domainName": "string",
     *   "domainType": "string",
     *   "lob": "string",
     *   "env": "string",
     *   "elements": [
     *     { "name": "string", "pattern": "string", "value": any }
     *   ]
     * }
     */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> updateConfigs(
            @RequestBody Map<String, List<Map<String, Object>>> request) {

        List<Map<String, Object>> oldConfigs = request.getOrDefault("old", Collections.emptyList());
        List<Map<String, Object>> newConfigs = request.getOrDefault("new", Collections.emptyList());

        List<String> errors = new ArrayList<>();
        List<String> successes = new ArrayList<>();

        try {
            // Build a map of old configs by key for lookup
            Map<String, Map<String, Object>> oldByKey = new HashMap<>();
            for (Map<String, Object> oldConfig : oldConfigs) {
                String key = buildConfigKey(oldConfig);
                oldByKey.put(key, oldConfig);
            }

            // Process new configs
            for (Map<String, Object> newConfig : newConfigs) {
                String key = buildConfigKey(newConfig);
                Map<String, Object> matchingOld = oldByKey.remove(key);

                try {
                    if (matchingOld != null) {
                        // Has matching old config -> REPLACE (delete old, write new)
                        processReplace(matchingOld, newConfig);
                        successes.add("Replaced: " + key);
                    } else {
                        // No matching old -> INSERT or MERGE
                        processInsertOrMerge(newConfig);
                        successes.add("Added/Merged: " + key);
                    }
                } catch (Exception e) {
                    errors.add(key + ": " + e.getMessage());
                    log.error("Failed to process config {}: {}", key, e.getMessage(), e);
                }
            }

            // Process remaining old configs (no matching new -> DELETE)
            for (Map.Entry<String, Map<String, Object>> entry : oldByKey.entrySet()) {
                try {
                    processDelete(entry.getValue());
                    successes.add("Deleted: " + entry.getKey());
                } catch (Exception e) {
                    errors.add(entry.getKey() + ": " + e.getMessage());
                    log.error("Failed to delete config {}: {}", entry.getKey(), e.getMessage(), e);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", errors.isEmpty());
            response.put("processed", successes.size());
            response.put("successes", successes);

            if (!errors.isEmpty()) {
                response.put("errors", errors);
                response.put("message", "Completed with " + errors.size() + " error(s)");
                return ResponseEntity.ok(response);
            }

            response.put("message", "All configs updated successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Update failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Replace old config with new (full replacement)
     */
    private void processReplace(Map<String, Object> oldConfig, Map<String, Object> newConfig) {
        String lob = getString(newConfig, "lob", "default");
        String domainName = getString(newConfig, "domainName", "");
        String domainType = getString(newConfig, "domainType", "");
        String env = getString(newConfig, "env", "ALL");

        ConfigPath configPath = ConfigPath.builder()
                .basePath(basePath)
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();

        // Delete existing directory
        if (fileService.directoryExists(configPath.toDomainTypeDir())) {
            fileService.deleteDirectory(configPath.toDomainTypeDir());
        }

        // Write new config
        writeConfig(newConfig);
    }

    /**
     * Insert new config or merge elements with existing
     */
    private void processInsertOrMerge(Map<String, Object> newConfig) {
        String lob = getString(newConfig, "lob", "default");
        String domainName = getString(newConfig, "domainName", "");
        String domainType = getString(newConfig, "domainType", "");
        String env = getString(newConfig, "env", "ALL");

        ConfigPath configPath = ConfigPath.builder()
                .basePath(basePath)
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();

        if (fileService.fileExists(configPath.toMetaPath())) {
            // Config exists -> merge elements
            mergeElements(newConfig, configPath, env);
        } else {
            // New config -> write all
            writeConfig(newConfig);
        }
    }

    /**
     * Merge new elements into existing config (only adds/updates specified elements)
     */
    private void mergeElements(Map<String, Object> newConfig, ConfigPath configPath, String env) {
        String lob = getString(newConfig, "lob", "default");
        String domainName = getString(newConfig, "domainName", "");
        String domainType = getString(newConfig, "domainType", "");

        List<Map<String, Object>> newElements = (List<Map<String, Object>>) newConfig.getOrDefault("elements", Collections.emptyList());

        // Read existing meta file
        var metaFile = fileService.readMetaFile(configPath.toMetaPath());
        Set<String> existingElementNames = new HashSet<>();
        if (metaFile.getElements() != null) {
            metaFile.getElements().forEach(e -> existingElementNames.add(e.getName()));
        }

        List<com.salescode.cofman.model.dto.MetaElement> metaElements = new ArrayList<>(
                metaFile.getElements() != null ? metaFile.getElements() : Collections.emptyList()
        );

        // Process each new element
        for (Map<String, Object> elementMap : newElements) {
            String elementName = getString(elementMap, "name", "element");
            String patternStr = getString(elementMap, "pattern", "FALLBACK");
            Object value = elementMap.get("value");

            ConfigPath elementPath = ConfigPath.builder()
                    .basePath(basePath)
                    .lob(lob)
                    .domainName(domainName)
                    .domainType(domainType)
                    .elementName(elementName)
                    .env(env)
                    .build();

            // Ensure element directory exists
            fileService.ensureDirectory(elementPath.toElementDir());

            // Write element value
            JsonNode valueNode = objectMapper.valueToTree(value);
            fileService.writeJsonNode(elementPath.toEnvFile(), valueNode);

            // Add to meta if not already present
            if (!existingElementNames.contains(elementName)) {
                com.salescode.cofman.model.dto.MetaElement metaElement = new com.salescode.cofman.model.dto.MetaElement();
                metaElement.setName(elementName);
                metaElement.setPattern(com.salescode.cofman.model.enums.ElementPattern.valueOf(patternStr));
                metaElements.add(metaElement);
                existingElementNames.add(elementName);
            }
        }

        // Update meta file
        metaFile.setElements(metaElements);
        fileService.writeMetaFile(configPath.toMetaPath(), metaFile);
    }

    /**
     * Delete config entirely
     */
    private void processDelete(Map<String, Object> config) {
        String lob = getString(config, "lob", "default");
        String domainName = getString(config, "domainName", "");
        String domainType = getString(config, "domainType", "");

        ConfigPath configPath = ConfigPath.builder()
                .basePath(basePath)
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();

        if (!fileService.directoryExists(configPath.toDomainTypeDir())) {
            throw new RuntimeException("Config not found: " + buildConfigKey(config));
        }

        fileService.deleteDirectory(configPath.toDomainTypeDir());
    }

    /**
     * Write a complete config to filesystem
     */
    private void writeConfig(Map<String, Object> config) {
        String lob = getString(config, "lob", "default");
        String domainName = getString(config, "domainName", "");
        String domainType = getString(config, "domainType", "");
        String env = getString(config, "env", "ALL");

        List<Map<String, Object>> elements = (List<Map<String, Object>>) config.getOrDefault("elements", Collections.emptyList());

        ConfigPath basePath = ConfigPath.builder()
                .basePath(this.basePath)
                .lob(lob)
                .domainName(domainName)
                .domainType(domainType)
                .build();

        // Create directory
        fileService.ensureDirectory(basePath.toDomainTypeDir());

        List<com.salescode.cofman.model.dto.MetaElement> metaElements = new ArrayList<>();

        // Write each element
        for (Map<String, Object> elementMap : elements) {
            String elementName = getString(elementMap, "name", "element");
            String patternStr = getString(elementMap, "pattern", "FALLBACK");
            Object value = elementMap.get("value");

            ConfigPath elementPath = ConfigPath.builder()
                    .basePath(this.basePath)
                    .lob(lob)
                    .domainName(domainName)
                    .domainType(domainType)
                    .elementName(elementName)
                    .env(env)
                    .build();

            fileService.ensureDirectory(elementPath.toElementDir());

            JsonNode valueNode = objectMapper.valueToTree(value);
            fileService.writeJsonNode(elementPath.toEnvFile(), valueNode);

            // Add to meta
            com.salescode.cofman.model.dto.MetaElement metaElement = new com.salescode.cofman.model.dto.MetaElement();
            metaElement.setName(elementName);
            try {
                metaElement.setPattern(com.salescode.cofman.model.enums.ElementPattern.valueOf(patternStr));
            } catch (IllegalArgumentException e) {
                metaElement.setPattern(com.salescode.cofman.model.enums.ElementPattern.FALLBACK);
            }
            metaElements.add(metaElement);
        }

        // Write _meta.json
        com.salescode.cofman.model.dto.MetaFile metaFile = new com.salescode.cofman.model.dto.MetaFile();
        metaFile.setDomainName(domainName);
        metaFile.setDomainType(domainType);
        metaFile.setElements(metaElements);
        fileService.writeMetaFile(basePath.toMetaPath(), metaFile);
    }

    /**
     * Build unique key for config lookup
     */
    private String buildConfigKey(Map<String, Object> config) {
        return getString(config, "domainName", "") + "|" +
                getString(config, "domainType", "") + "|" +
                getString(config, "lob", "default") + "|" +
                getString(config, "env", "ALL");
    }

    /**
     * Safely get string from map
     */
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * GET /api/config/lobs
     * Returns list of all available LOBs
     */
    @GetMapping("/lobs")
    public ResponseEntity<List<String>> getAllLobs() {
        try {
            Set<String> lobs = new TreeSet<>();
            lobs.add("default"); // Always include default

            java.nio.file.Path configRoot = java.nio.file.Paths.get(basePath);
            if (java.nio.file.Files.exists(configRoot)) {
                try (var stream = java.nio.file.Files.list(configRoot)) {
                    stream.filter(java.nio.file.Files::isDirectory)
                            .forEach(p -> lobs.add(p.getFileName().toString()));
                }
            }

            return ResponseEntity.ok(new ArrayList<>(lobs));
        } catch (Exception e) {
            log.error("Failed to get LOBs: {}", e.getMessage());
            return ResponseEntity.ok(List.of("default"));
        }
    }

    /**
     * GET /api/config/envs
     * Returns list of supported environments
     */
    @GetMapping("/envs")
    public ResponseEntity<List<String>> getAllEnvs() {
        return ResponseEntity.ok(List.of("ALL", "UAT", "DEV", "DEMO", "PROD"));
    }
}
