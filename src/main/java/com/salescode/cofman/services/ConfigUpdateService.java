package com.salescode.cofman.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.model.dto.ConfigPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigUpdateService {

    private static final String DEFAULT_LOB = "default";

    @Value("${config.base-path:src/main/resources/configs}")
    private String basePath;

    private final ObjectMapper objectMapper;
    private ConfigRepositoryService repository;
    private ConfigTransformService transformService;

    @PostConstruct
    public void init() {
        this.repository = new ConfigRepositoryService(basePath);
        this.transformService = new ConfigTransformService(repository);
    }

    /**
     * Execute config update AFTER approval
     */
    public Map<String, Object> executeConfigUpdate(Map<String, List<Map<String, Object>>> request) {
        List<Map<String, Object>> oldConfigs = request.getOrDefault("old", Collections.emptyList());
        List<Map<String, Object>> newConfigs = request.getOrDefault("new", Collections.emptyList());

        List<String> errors = new ArrayList<>();
        List<String> successes = new ArrayList<>();

        try {
            // Validate
            List<String> validationErrors = validateConfigs(oldConfigs, newConfigs);
            if (!validationErrors.isEmpty()) {
                return Map.of("success", false, "errors", validationErrors);
            }

            // Build old configs map
            Map<String, Map<String, Object>> oldByKey = new HashMap<>();
            for (Map<String, Object> oldConfig : oldConfigs) {
                oldByKey.put(buildConfigKey(oldConfig), oldConfig);
            }

            // Process new configs
            for (Map<String, Object> newConfig : newConfigs) {
                String key = buildConfigKey(newConfig);
                Map<String, Object> matchingOld = oldByKey.remove(key);

                try {
                    if (matchingOld != null) {
                        processReplace(matchingOld, newConfig);
                        successes.add("Replaced: " + key);
                    } else {
                        processInsertOrMerge(newConfig);
                        successes.add("Added/Merged: " + key);
                    }
                } catch (Exception e) {
                    errors.add(key + ": " + e.getMessage());
                    log.error("Failed to process config {}", key, e);
                }
            }

            // Process deletes
            for (Map.Entry<String, Map<String, Object>> entry : oldByKey.entrySet()) {
                try {
                    processDelete(entry.getValue());
                    successes.add("Deleted: " + entry.getKey());
                } catch (Exception e) {
                    errors.add(entry.getKey() + ": " + e.getMessage());
                    log.error("Failed to delete config {}", entry.getKey(), e);
                }
            }

            return Map.of(
                    "success", errors.isEmpty(),
                    "processed", successes.size(),
                    "successes", successes,
                    "errors", errors
            );

        } catch (Exception e) {
            log.error("Update failed", e);
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private List<String> validateConfigs(List<Map<String, Object>> oldConfigs,
                                         List<Map<String, Object>> newConfigs) {
        List<String> errors = new ArrayList<>();

        Set<String> configsInBatch = new HashSet<>();
        for (Map<String, Object> config : newConfigs) {
            String domainName = getString(config, "domainName", "");
            String domainType = getString(config, "domainType", "");
            String lob = getString(config, "lob", DEFAULT_LOB);
            configsInBatch.add(domainName + "|" + domainType + "|" + lob);
        }

        for (Map<String, Object> config : newConfigs) {
            String lob = getString(config, "lob", DEFAULT_LOB);
            String domainName = getString(config, "domainName", "");
            String domainType = getString(config, "domainType", "");
            String env = getString(config, "env", "ALL");

            if (DEFAULT_LOB.equals(lob)) {
                continue;
            }

            String defaultConfigKey = domainName + "|" + domainType + "|" + DEFAULT_LOB;
            boolean defaultInBatch = configsInBatch.contains(defaultConfigKey);
            boolean defaultOnFilesystem = isDefaultPresentOnFilesystem(domainName, domainType);

            if (!defaultInBatch && !defaultOnFilesystem) {
                errors.add(String.format(
                        "Default LOB must exist before creating/updating '%s/%s' for LOB '%s'",
                        domainName, domainType, lob
                ));
            }

            if (env == null || env.isEmpty() || "null".equals(env)) {
                errors.add(String.format(
                        "Environment required for non-default LOB: %s/%s/%s",
                        lob, domainName, domainType
                ));
            }
        }

        return errors;
    }

    private boolean isDefaultPresentOnFilesystem(String domainName, String domainType) {
        ConfigPath path = repository.buildPath(DEFAULT_LOB, domainName, domainType);
        return repository.fileExists(path.toMetaPath());
    }

    private void processReplace(Map<String, Object> oldConfig, Map<String, Object> newConfig) {
        String lob = getString(newConfig, "lob", DEFAULT_LOB);
        String domainName = getString(newConfig, "domainName", "");
        String domainType = getString(newConfig, "domainType", "");

        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);

        if (repository.directoryExists(configPath.toDomainTypeDir())) {
            repository.deleteDirectory(configPath.toDomainTypeDir());
        }

        writeConfig(newConfig);
    }

    private void processInsertOrMerge(Map<String, Object> newConfig) {
        String lob = getString(newConfig, "lob", DEFAULT_LOB);
        String domainName = getString(newConfig, "domainName", "");
        String domainType = getString(newConfig, "domainType", "");
        String env = getString(newConfig, "env", "ALL");

        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);

        if (repository.fileExists(configPath.toMetaPath())) {
            mergeElements(newConfig, configPath, env);
        } else {
            writeConfig(newConfig);
        }
    }

    private void mergeElements(Map<String, Object> newConfig, ConfigPath configPath, String env) {
        String lob = getString(newConfig, "lob", DEFAULT_LOB);
        String domainName = getString(newConfig, "domainName", "");
        String domainType = getString(newConfig, "domainType", "");

        List<Map<String, Object>> newElements =
                (List<Map<String, Object>>) newConfig.getOrDefault("elements", Collections.emptyList());

        var metaFile = repository.readMetaFile(configPath.toMetaPath());
        Set<String> existingElementNames = new HashSet<>();
        if (metaFile.getElements() != null) {
            metaFile.getElements().forEach(e -> existingElementNames.add(e.getName()));
        }

        List<com.salescode.cofman.model.dto.MetaElement> metaElements = new ArrayList<>(
                metaFile.getElements() != null ? metaFile.getElements() : Collections.emptyList()
        );

        List<String> envs = "all".equalsIgnoreCase(env)
                ? List.of("uat", "demo", "prod")
                : List.of(env);

        for (Map<String, Object> elementMap : newElements) {
            String elementName = getString(elementMap, "name", "element");
            String patternStr = getString(elementMap, "pattern", "FALLBACK");
            Object value = elementMap.get("value");

            for (String e : envs) {
                Object transformedValue = transformService.replaceDynamicValues(
                        objectMapper.convertValue(value, JsonNode.class), e
                );

                ConfigPath elementPath = repository.buildPathWithEnv(
                        lob, domainName, domainType, elementName, e
                );

                repository.ensureDirectory(elementPath.toElementDir());

                JsonNode valueNode = objectMapper.valueToTree(transformedValue);
                repository.writeJsonNode(elementPath.toEnvFile(), valueNode);
            }

            if (!existingElementNames.contains(elementName)) {
                com.salescode.cofman.model.dto.MetaElement metaElement =
                        new com.salescode.cofman.model.dto.MetaElement();
                metaElement.setName(elementName);
                metaElement.setPattern(
                        com.salescode.cofman.model.enums.ElementPattern.valueOf(patternStr)
                );
                metaElements.add(metaElement);
                existingElementNames.add(elementName);
            }
        }

        metaFile.setElements(metaElements);
        repository.writeMetaFile(configPath.toMetaPath(), metaFile);
    }

    private void processDelete(Map<String, Object> config) {
        String lob = getString(config, "lob", DEFAULT_LOB);
        String domainName = getString(config, "domainName", "");
        String domainType = getString(config, "domainType", "");

        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);

        if (!repository.directoryExists(configPath.toDomainTypeDir())) {
            throw new RuntimeException("Config not found: " + buildConfigKey(config));
        }

        repository.deleteDirectory(configPath.toDomainTypeDir());
    }

    private void writeConfig(Map<String, Object> config) {
        String lob = getString(config, "lob", DEFAULT_LOB);
        String domainName = getString(config, "domainName", "");
        String domainType = getString(config, "domainType", "");
        String env = getString(config, "env", "ALL");

        List<Map<String, Object>> elements =
                (List<Map<String, Object>>) config.getOrDefault("elements", Collections.emptyList());

        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);
        repository.ensureDirectory(basePath.toDomainTypeDir());

        List<com.salescode.cofman.model.dto.MetaElement> metaElements = new ArrayList<>();

        List<String> envs = "ALL".equalsIgnoreCase(env)
                ? List.of("uat", "demo", "prod")
                : List.of(env);

        for (Map<String, Object> elementMap : elements) {
            String elementName = getString(elementMap, "name", "element");
            String patternStr = getString(elementMap, "pattern", "FALLBACK");
            Object value = elementMap.get("value");

            for (String e : envs) {
                Object transformedValue = transformService.replaceDynamicValues(
                        objectMapper.convertValue(value, JsonNode.class), e
                );

                ConfigPath elementPath = repository.buildPathWithEnv(
                        lob, domainName, domainType, elementName, e
                );

                repository.ensureDirectory(elementPath.toElementDir());

                JsonNode valueNode = objectMapper.valueToTree(transformedValue);
                repository.writeJsonNode(elementPath.toEnvFile(), valueNode);
            }

            com.salescode.cofman.model.dto.MetaElement metaElement =
                    new com.salescode.cofman.model.dto.MetaElement();
            metaElement.setName(elementName);
            try {
                metaElement.setPattern(
                        com.salescode.cofman.model.enums.ElementPattern.valueOf(patternStr)
                );
            } catch (IllegalArgumentException ex) {
                metaElement.setPattern(
                        com.salescode.cofman.model.enums.ElementPattern.FALLBACK
                );
            }
            metaElements.add(metaElement);
        }

        com.salescode.cofman.model.dto.MetaFile metaFile =
                new com.salescode.cofman.model.dto.MetaFile();
        metaFile.setDomainName(domainName);
        metaFile.setDomainType(domainType);
        metaFile.setElements(metaElements);
        repository.writeMetaFile(basePath.toMetaPath(), metaFile);
    }

    private String buildConfigKey(Map<String, Object> config) {
        return getString(config, "domainName", "") + "|" +
                getString(config, "domainType", "") + "|" +
                getString(config, "lob", DEFAULT_LOB) + "|" +
                getString(config, "env", "ALL");
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}