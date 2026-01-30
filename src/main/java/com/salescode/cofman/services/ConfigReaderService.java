package com.salescode.cofman.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.model.dto.MetaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

@Service
public class ConfigReaderService {

    private static final String META_FILE = "_meta.json";
    private static final String DEFAULT_LOB = "default";
    private static final Logger log = LoggerFactory.getLogger(ConfigReaderService.class);

    @Value("${config.base-path:src/main/resources/configs}")
    private String basePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Copy all configs from source LOB to target LOB
     */
    public List<String> copyLob(String fromLob, String toLob, String env) {
        List<String> copiedConfigs = new ArrayList<>();

        if (fromLob.equals(toLob)) {
            throw new RuntimeException("Source and target LOB cannot be the same");
        }

        Path sourceLobPath = Path.of(basePath, fromLob);

        if (!Files.exists(sourceLobPath) || !Files.isDirectory(sourceLobPath)) {
            throw new RuntimeException("Source LOB not found: " + fromLob);
        }

        try (Stream<Path> domainStream = Files.list(sourceLobPath)) {
            domainStream.filter(Files::isDirectory).forEach(domainPath -> {
                String domainName = domainPath.getFileName().toString();

                try (Stream<Path> typeStream = Files.list(domainPath)) {
                    typeStream.filter(Files::isDirectory).forEach(typePath -> {
                        String domainType = typePath.getFileName().toString();

                        try {
                            copyDomainConfig(fromLob, toLob, domainName, domainType, env);
                            String key = domainName + "/" + domainType;
                            if (env != null) {
                                key += " (env: " + env + ")";
                            }
                            copiedConfigs.add(key);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to copy " + domainName + "/" + domainType + ": " + e.getMessage(), e);
                        }
                    });
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read domain types in: " + domainPath, e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read source LOB: " + fromLob, e);
        }

        return copiedConfigs;
    }

    private void copyDomainConfig(String fromLob, String toLob, String domainName, String domainType, String env) {
        Path sourcePath = Path.of(basePath, fromLob, domainName, domainType);
        Path targetPath = Path.of(basePath, toLob, domainName, domainType);

        Path sourceMetaFile = sourcePath.resolve("_meta.json");
        Path targetMetaFile = targetPath.resolve("_meta.json");

        if (!Files.exists(sourceMetaFile)) {
            throw new RuntimeException("Source meta file not found: " + sourceMetaFile);
        }

        try {
            // Create target directory
            Files.createDirectories(targetPath);

            // Read meta file to get element names
            ObjectMapper mapper = new ObjectMapper();
            JsonNode metaJson = mapper.readTree(sourceMetaFile.toFile());
            JsonNode elements = metaJson.get("elements");

            if (elements != null && elements.isArray()) {
                for (JsonNode element : elements) {
                    String elementName = element.get("name").asText();

                    Path sourceElementDir = sourcePath.resolve(elementName);
                    Path targetElementDir = targetPath.resolve(elementName);

                    if (!Files.exists(sourceElementDir)) {
                        continue;
                    }

                    // Create target element directory
                    Files.createDirectories(targetElementDir);

                    if (env != null) {
                        // Copy only specific environment file
                        Path sourceEnvFile = sourceElementDir.resolve(env + ".json");
                        Path targetEnvFile = targetElementDir.resolve(env + ".json");

                        if (Files.exists(sourceEnvFile)) {
                            Files.copy(sourceEnvFile, targetEnvFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        // Copy all environment files
                        try (Stream<Path> files = Files.list(sourceElementDir)) {
                            files.filter(Files::isRegularFile)
                                    .filter(f -> f.toString().endsWith(".json"))
                                    .forEach(sourceFile -> {
                                        try {
                                            String fileName = sourceFile.getFileName().toString();
                                            Path targetFile = targetElementDir.resolve(fileName);
                                            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                                        } catch (IOException e) {
                                            throw new RuntimeException("Failed to copy file: " + sourceFile, e);
                                        }
                                    });
                        }
                    }
                }
            }

            // Copy meta file
            Files.copy(sourceMetaFile, targetMetaFile, StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            throw new RuntimeException("Failed to copy domain config: " + e.getMessage(), e);
        }
    }
    /**
     * Get all config summaries (domainName, domainType, elements)
     */
    public List<Map<String, Object>> getAllConfigSummaries() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        Path configRoot = Paths.get(basePath);

        if (!Files.exists(configRoot)) {
            return summaries;
        }

        Set<String> processed = new HashSet<>();

        try (Stream<Path> lobDirs = Files.list(configRoot)) {
            lobDirs.filter(Files::isDirectory).forEach(lobDir -> {
                try (Stream<Path> domainDirs = Files.list(lobDir)) {
                    domainDirs.filter(Files::isDirectory).forEach(domainDir -> {
                        String domainName = domainDir.getFileName().toString();

                        try (Stream<Path> typeDirs = Files.list(domainDir)) {
                            typeDirs.filter(Files::isDirectory).forEach(typeDir -> {
                                String domainType = typeDir.getFileName().toString();
                                String key = domainName + "/" + domainType;

                                if (!processed.contains(key)) {
                                    processed.add(key);
                                    Map<String, Object> summary = buildSummary(domainName, domainType, typeDir);
                                    if (summary != null) {
                                        summaries.add(summary);
                                    }
                                }
                            });
                        } catch (IOException ignored) {
                            log.error("error");
                        }
                    });
                } catch (IOException ignored) {
                    log.error("error");
                }
            });
        } catch (IOException ignored) {
            log.error("error");
        }

        return summaries;
    }

    /**
     * Get all configurations for a specific LOB and environment
     *
     * @param lob LOB name (uses "default" if null/empty)
     * @param env Environment name (finds first available if null/empty)
     * @return List of configuration objects with domain, type, element details
     */
    public List<Map<String, Object>> getConfigsByLobAndEnv(String lob, String env) {
        List<Map<String, Object>> results = new ArrayList<>();
        String targetLob = (lob != null && !lob.isEmpty()) ? lob : DEFAULT_LOB;
        Path lobPath = Paths.get(basePath).resolve(targetLob);

        if (!Files.exists(lobPath) || !Files.isDirectory(lobPath)) {
            return results;
        }

        try (Stream<Path> domainDirs = Files.list(lobPath)) {
            domainDirs.filter(Files::isDirectory).forEach(domainDir -> {
                String domainName = domainDir.getFileName().toString();

                try (Stream<Path> typeDirs = Files.list(domainDir)) {
                    typeDirs.filter(Files::isDirectory).forEach(typeDir -> {
                        String domainType = typeDir.getFileName().toString();
                        Path metaPath = typeDir.resolve(META_FILE);

                        if (Files.exists(metaPath)) {
                            try {
                                MetaFile metaFile = objectMapper.readValue(metaPath.toFile(), MetaFile.class);

                                if (metaFile.getElements() != null) {
                                    for (var metaElement : metaFile.getElements()) {
                                        Path elementDir = typeDir.resolve(metaElement.getName());

                                        if (Files.exists(elementDir) && Files.isDirectory(elementDir)) {
                                            JsonNode value = readElementValue(elementDir, env);

                                            if (value != null) {
                                                Map<String, Object> config = new LinkedHashMap<>();
                                                config.put("domainName", domainName);
                                                config.put("domainType", domainType);
                                                config.put("elementName", metaElement.getName());
                                                config.put("pattern", metaElement.getPattern() != null
                                                        ? metaElement.getPattern().name() : null);
                                                config.put("lob", targetLob);
                                                config.put("env", resolveEnvName(elementDir, env));
                                                config.put("value", value);

                                                results.add(config);
                                            }
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                log.error("Error reading meta file: {}", metaPath, e);
                            }
                        }
                    });
                } catch (IOException e) {
                    log.error("Error listing type directories in: {}", domainDir, e);
                }
            });
        } catch (IOException e) {
            log.error("Error listing domain directories in: {}", lobPath, e);
        }

        return results;
    }

    private JsonNode readElementValue(Path elementDir, String env) {
        try {
            Path envFile;

            if (env != null && !env.isEmpty()) {
                envFile = elementDir.resolve(env + ".json");
                if (!Files.exists(envFile)) {
                    return null;
                }
            } else {
                try (Stream<Path> files = Files.list(elementDir)) {
                    Optional<Path> firstJson = files
                            .filter(f -> f.toString().endsWith(".json"))
                            .findFirst();

                    if (firstJson.isEmpty()) {
                        return null;
                    }
                    envFile = firstJson.get();
                }
            }

            return objectMapper.readTree(envFile.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private String resolveEnvName(Path elementDir, String env) {
        if (env != null && !env.isEmpty()) {
            return env;
        }

        try (Stream<Path> files = Files.list(elementDir)) {
            return files
                    .filter(f -> f.toString().endsWith(".json"))
                    .findFirst()
                    .map(f -> {
                        String fileName = f.getFileName().toString();
                        return fileName.substring(0, fileName.length() - 5);
                    })
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
    private Map<String, Object> buildSummary(String domainName, String domainType, Path typeDir) {
        Path metaPath = typeDir.resolve(META_FILE);

        if (!Files.exists(metaPath)) {
            return null;
        }

        try {
            MetaFile metaFile = objectMapper.readValue(metaPath.toFile(), MetaFile.class);
            List<String> elementNames = new ArrayList<>();

            if (metaFile.getElements() != null) {
                metaFile.getElements().forEach(e -> elementNames.add(e.getName()));
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("domainName", domainName);
            summary.put("domainType", domainType);
            summary.put("elements", elementNames);

            return summary;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get detailed info for a specific domain across all LOBs
     */
    public Map<String, Object> getDomainDetail(String domainName, String domainType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domainName", domainName);
        result.put("domainType", domainType);

        Map<String, Object> lobs = new LinkedHashMap<>();
        Path configRoot = Paths.get(basePath);

        if (!Files.exists(configRoot)) {
            result.put("lobs", lobs);
            return result;
        }

        try (Stream<Path> lobDirs = Files.list(configRoot)) {
            lobDirs.filter(Files::isDirectory).forEach(lobDir -> {
                String lobName = lobDir.getFileName().toString();
                Path typeDir = lobDir.resolve(domainName).resolve(domainType);

                if (Files.exists(typeDir) && Files.isDirectory(typeDir)) {
                    Map<String, Object> lobDetail = buildLobDetail(typeDir);
                    if (lobDetail != null) {
                        lobs.put(lobName, lobDetail);
                    }
                }
            });
        } catch (IOException ignored) {}

        result.put("lobs", lobs);
        return result;
    }

    private Map<String, Object> buildLobDetail(Path typeDir) {
        Path metaPath = typeDir.resolve(META_FILE);

        if (!Files.exists(metaPath)) {
            return null;
        }

        try {
            MetaFile metaFile = objectMapper.readValue(metaPath.toFile(), MetaFile.class);
            List<Map<String, Object>> elements = new ArrayList<>();
            String env = null;

            if (metaFile.getElements() != null) {
                for (var metaElement : metaFile.getElements()) {
                    Path elementDir = typeDir.resolve(metaElement.getName());

                    if (Files.exists(elementDir) && Files.isDirectory(elementDir)) {
                        try (Stream<Path> envFiles = Files.list(elementDir)) {
                            Optional<Path> envFile = envFiles
                                    .filter(f -> f.toString().endsWith(".json"))
                                    .findFirst();

                            if (envFile.isPresent()) {
                                Path file = envFile.get();
                                String fileName = file.getFileName().toString();
                                env = fileName.substring(0, fileName.length() - 5);

                                JsonNode value = objectMapper.readTree(file.toFile());

                                Map<String, Object> element = new LinkedHashMap<>();
                                element.put("name", metaElement.getName());
                                element.put("pattern", metaElement.getPattern() != null ? metaElement.getPattern().name() : null);
                                element.put("value", value);

                                elements.add(element);
                            }
                        } catch (IOException ignored) {}
                    }
                }
            }

            Map<String, Object> lobDetail = new LinkedHashMap<>();
            lobDetail.put("env", env);
            lobDetail.put("elements", elements);

            return lobDetail;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get specific element value
     *
     * @param domainName Domain name
     * @param domainType Domain type
     * @param name Element name
     * @param lob LOB (optional, defaults to "default")
     * @param env Environment (optional, finds first available if not specified)
     */
    public Object getElementValue(String domainName, String domainType, String name, String lob, String env) {
        String targetLob = (lob != null && !lob.isEmpty()) ? lob : DEFAULT_LOB;
        Path configRoot = Paths.get(basePath);
        Path elementDir = configRoot.resolve(targetLob).resolve(domainName).resolve(domainType).resolve(name);

        if (!Files.exists(elementDir) || !Files.isDirectory(elementDir)) {
            // Try default LOB if specific LOB not found
            if (!DEFAULT_LOB.equals(targetLob)) {
                elementDir = configRoot.resolve(DEFAULT_LOB).resolve(domainName).resolve(domainType).resolve(name);
                if (!Files.exists(elementDir) || !Files.isDirectory(elementDir)) {
                    return null;
                }
            } else {
                return null;
            }
        }

        try {
            Path envFile;

            if (env != null && !env.isEmpty()) {
                // Specific env requested
                envFile = elementDir.resolve(env + ".json");
                if (!Files.exists(envFile)) {
                    return null;
                }
            } else {
                // Find first available env file
                try (Stream<Path> files = Files.list(elementDir)) {
                    Optional<Path> firstJson = files
                            .filter(f -> f.toString().endsWith(".json"))
                            .findFirst();

                    if (firstJson.isEmpty()) {
                        return null;
                    }
                    envFile = firstJson.get();
                }
            }

            return objectMapper.readTree(envFile.toFile());
        } catch (IOException e) {
            return null;
        }
    }


}