package com.salescode.cofman.services;

import com.salescode.cofman.exception.ConfigNotFoundException;
import com.salescode.cofman.exception.ConfigOperationException;
import com.salescode.cofman.model.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salescode.cofman.model.enums.Action;
import com.salescode.cofman.model.enums.ElementPattern;
import com.salescode.cofman.util.ElementParser;
import com.salescode.cofman.util.FileNameSanitizer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.*;

/**
 * Transform Service - Handles conversion between JSON arrays and folder structures.
 *
 * Consolidates:
 * - DeconstructService (100%)
 * - ReconstructService (100%)
 *
 * NO LOGIC CHANGES - Pure extraction
 */
@Slf4j
@Getter
public class ConfigTransformService {

    private static final List<String> SUPPORTED_ENVS =
            List.of("ALL", "UAT", "DEV", "DEMO", "PROD");

    private final ConfigRepositoryService repository;
    private final ObjectMapper objectMapper;
    private final String defaultLob;
    private final String defaultEnv;

    public ConfigTransformService(ConfigRepositoryService repository) {
        this(repository, "default", "ALL");
    }

    public ConfigTransformService(ConfigRepositoryService repository, String defaultLob, String defaultEnv) {
        this.repository = repository;
        this.objectMapper = repository.getObjectMapper();
        this.defaultLob = defaultLob;
        this.defaultEnv = defaultEnv;
    }

    // ==================== DECONSTRUCT (From DeconstructService - NO CHANGES) ====================

    /**
     * Deconstruct a JSON array into folder structure.
     *
     * @param lob Line of Business
     * @param domainName Domain name
     * @param domainType Domain type
     * @param jsonArray The JSON array to deconstruct (domain_values)
     * @return DomainConfig representing what was created
     */
    public DomainConfig deconstruct(String lob, String domainName, String domainType, JsonNode jsonArray,String env) {
        if (!jsonArray.isArray()) {
            throw new ConfigOperationException("deconstruct",
                    "Expected JSON array, got: " + jsonArray.getNodeType());
        }

        String sanitizedDomainName = FileNameSanitizer.sanitize(domainName);
        String sanitizedDomainType = FileNameSanitizer.sanitize(domainType);

        DomainConfig domainConfig = new DomainConfig(lob, sanitizedDomainName, sanitizedDomainType,
                Action.INSERT, env, new ArrayList<>());

        ConfigPath basePath = repository.buildPath(lob, sanitizedDomainName, sanitizedDomainType);

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
                // Write element JSON file for all environments
                List<String> envsToWrite = getEnvsToWrite(env);

                for (String targetEnv : envsToWrite) {
                    ConfigPath elementPath = repository.buildPathWithEnv(lob, sanitizedDomainName,
                            sanitizedDomainType, finalName, targetEnv);

                    repository.ensureDirectory(elementPath.toElementDir());
                    JsonNode resolvedValue = replaceDynamicValues(configElement.getValue(), targetEnv);
                    repository.writeJsonNode(elementPath.toEnvFile(), resolvedValue);
                }
                domainConfig.addElement(configElement);
            }
        }

        // Write _meta.json
        MetaFile metaFile = domainConfig.toMetaFile();
        repository.writeMetaFile(basePath.toMetaPath(), metaFile);

        return domainConfig;
    }

    /**
     * Deconstruct with default LOB
     */
    public DomainConfig deconstruct(String domainName, String domainType, JsonNode jsonArray) {
        return deconstruct(defaultLob, domainName, domainType, jsonArray,defaultEnv);
    }

    /**
     * Deconstruct from a raw JSON string
     */
    public DomainConfig deconstructFromString(String lob, String domainName, String domainType,
                                              String jsonArrayString) {
        try {
            JsonNode jsonArray = objectMapper.readTree(jsonArrayString);
            return deconstruct(lob, domainName, domainType, jsonArray,defaultEnv);
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

        for (Map<String, String> row : rows) {
            String domainName = row.get("domain_name");
            String domainType = row.get("domain_type");
            String domainValues = row.get("domain_values");

            if (domainName == null || domainType == null || domainValues == null) {
                log.warn("Skipping row with missing fields: {}", row);
                continue;
            }

            try {
                JsonNode jsonArray = objectMapper.readTree(domainValues);
                DomainConfig config = deconstruct(lob, domainName, domainType, jsonArray,defaultEnv);
                results.add(config);
                log.info("Deconstructed: {}/{}", domainName, domainType);
            } catch (Exception e) {
                log.error("Failed to deconstruct {}/{}: {}", domainName, domainType, e.getMessage(), e);
            }
        }

        return results;
    }

    // ==================== RECONSTRUCT (From ReconstructService - NO CHANGES) ====================

    /**
     * Reconstruct entire domain type to JSON array for all environments.
     *
     * @param lob Line of Business
     * @param domainName Domain name
     * @param domainType Domain type
     * @return List of ReconstructResult for each environment
     */
    public List<ReconstructResult> reconstruct(String lob, String domainName, String domainType) {
        List<ReconstructResult> results = new ArrayList<>();

        for (String env : SUPPORTED_ENVS) {
            try {
                ReconstructResult envResult = reconstruct(lob, domainName, domainType, env);
                results.add(envResult);
            } catch (ConfigNotFoundException ex) {
                // env config not present → skip, but don't fail
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
        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);
        Path metaPath = configPath.toMetaPath();

        if (!repository.fileExists(metaPath)) {
            throw new ConfigNotFoundException(domainName, domainType);
        }

        MetaFile metaFile = repository.readMetaFile(metaPath);
        ReconstructResult result = new ReconstructResult();
        result.setLob(lob);
        result.setDomainName(domainName);
        result.setDomainType(domainType);
        result.setEnv(env);

        ArrayNode jsonArray = objectMapper.createArrayNode();
        List<ConfigElement> elements = new ArrayList<>();

        // Load all elements with their values
        for (MetaElement metaElement : metaFile.getElements()) {
            ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType,
                    metaElement.getName(), env);
            Path envFilePath = elementPath.toEnvFile();

            if (!repository.fileExists(envFilePath)) {
                result.addWarning("Element folder missing: " + metaElement.getName());
                continue;
            }

            JsonNode value = repository.readJsonNode(envFilePath);
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
        // First read meta to get pattern info
        ConfigPath basePath = repository.buildPath(lob, domainName, domainType);
        Path metaPath = basePath.toMetaPath();

        if (!repository.fileExists(metaPath)) {
            throw new ConfigNotFoundException(domainName, domainType);
        }

        MetaFile metaFile = repository.readMetaFile(metaPath);
        MetaElement metaElement = metaFile.findElement(elementName);

        if (metaElement == null) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }

        ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType, elementName, env);
        Path envFilePath = elementPath.toEnvFile();

        if (!repository.fileExists(envFilePath)) {
            throw new ConfigNotFoundException(domainName, domainType, elementName);
        }

        JsonNode value = repository.readJsonNode(envFilePath);

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

        repository.listSubdirectories(domainTypeDir).forEach(subDir -> {
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

        ConfigPath configPath = repository.buildPath(lob, domainName, domainType);
        Path metaPath = configPath.toMetaPath();

        if (!repository.fileExists(metaPath)) {
            errors.add("Meta file not found: " + metaPath);
            return errors;
        }

        try {
            MetaFile metaFile = repository.readMetaFile(metaPath);

            // Check each element exists
            for (MetaElement element : metaFile.getElements()) {
                ConfigPath elementPath = repository.buildPathWithEnv(lob, domainName, domainType,
                        element.getName(), defaultEnv);

                if (!repository.fileExists(elementPath.toEnvFile())) {
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

    public JsonNode replaceDynamicValues(JsonNode node, String env) {
        JsonNode envVars = loadEnvVariables(env);
        return replaceRecursive(node, envVars);
    }

    private JsonNode loadEnvVariables(String env) {
        try {
            return objectMapper.readTree(
                    Objects.requireNonNull(
                            getClass().getClassLoader().getResourceAsStream("configs/dynamicValues/".concat(env.toLowerCase()) + ".json"),
                            "Env file not found: " + env
                    )
            );
        } catch (Exception e) {
            throw new ConfigOperationException("replaceDynamicValues",
                    "Failed to load env variables for env=" + env, e);
        }
    }

    private JsonNode replaceRecursive(JsonNode current, JsonNode envVars) {

        // "$.key" → resolve from env json
        if (current.isTextual()) {
            String value = current.asText();

            if (value.startsWith("$.")) {
                String key = value.substring(2);
                JsonNode replacement = envVars.get(key);

                if (replacement == null) {
                    throw new ConfigOperationException(
                            "replaceDynamicValues",
                            "Missing env variable: " + key
                    );
                }
                return replacement.deepCopy();
            }
            return current;
        }

        // Object
        if (current.isObject()) {
            ObjectNode obj = (ObjectNode) current;
            obj.fields().forEachRemaining(e ->
                    obj.set(e.getKey(), replaceRecursive(e.getValue(), envVars))
            );
            return obj;
        }

        // Array
        if (current.isArray()) {
            ArrayNode arr = (ArrayNode) current;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, replaceRecursive(arr.get(i), envVars));
            }
            return arr;
        }

        return current;
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

}