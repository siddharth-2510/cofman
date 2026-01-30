package com.salescode.cofman.services;

import com.salescode.cofman.model.Merge;
import com.salescode.cofman.model.dto.*;
import com.salescode.cofman.model.enums.Action;
import com.salescode.cofman.repository.MergeRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MergeService {

    private static final String DEFAULT_LOB = "default";
    private static final String DEFAULT_ENV = "ALL";

    @Autowired
    private MergeRepository mergeRepository;

    @Value("${config.base-path:src/main/resources/configs}")
    private String basePath;

    private ConfigFileService fileService;

    @PostConstruct
    public void init() {
        this.fileService = new ConfigFileService(basePath);
    }

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
     * - For INSERT/UPDATE on any LOB, default must exist (either in batch or on filesystem)
     */
    private List<String> validateConfigs(List<DomainConfig> configs) {
        List<String> errors = new ArrayList<>();

        for (DomainConfig config : configs) {
            if (config.getAction() == Action.INSERT || config.getAction() == Action.UPDATE) {
                if (!isDefaultPresent(config.getDomainName(), config.getDomainType(), configs)) {
                    errors.add("Default LOB must exist for: " +
                            config.getDomainName() + "/" + config.getDomainType());
                }
            }

            // Validate env is provided for non-default LOBs on INSERT/UPDATE
            if (!DEFAULT_LOB.equals(config.getLob()) &&
                    (config.getAction() == Action.INSERT || config.getAction() == Action.UPDATE)) {
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

        // Check filesystem
        ConfigPath path = ConfigPath.builder()
                .basePath(basePath)
                .lob(DEFAULT_LOB)
                .domainName(domainName)
                .domainType(domainType)
                .build();

        return fileService.fileExists(path.toMetaPath());
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
        ConfigPath configPath = buildConfigPath(config);

        if (fileService.fileExists(configPath.toMetaPath())) {
            log.error("Config already exists, use UPDATE");
            return;
        }

        writeConfig(config);
    }

    /**
     * UPDATE - overwrite existing config
     */
    private void updateConfig(DomainConfig config) {
        ConfigPath configPath = buildConfigPath(config);

        if (fileService.directoryExists(configPath.toDomainTypeDir())) {
            fileService.deleteDirectory(configPath.toDomainTypeDir());
        }

        writeConfig(config);
    }

    /**
     * DELETE - remove config
     */
    private void deleteConfig(DomainConfig config) {
        ConfigPath configPath = buildConfigPath(config);

        if (!fileService.directoryExists(configPath.toDomainTypeDir())) {
            throw new RuntimeException("Config not found: " + buildKey(config));
        }

        fileService.deleteDirectory(configPath.toDomainTypeDir());
    }

    /**
     * Write config to filesystem
     * - Default LOB: uses ALL.json (single version)
     * - Other LOBs: uses env-specific file (UAT.json, Demo.json, etc.)
     */
    private void writeConfig(DomainConfig config) {
        String lob = config.getLob();
        String domainName = config.getDomainName();
        String domainType = config.getDomainType();

        // Default LOB uses ALL.json, others use their specified env
        String env = config.getEnv();

        ConfigPath configPath = buildConfigPath(config);

        // Create directory
        fileService.ensureDirectory(configPath.toDomainTypeDir());

        // Write each element
        if (config.getElements() != null) {
            for (ConfigElement element : config.getElements()) {
                ConfigPath elementPath = ConfigPath.builder()
                        .basePath(basePath)
                        .lob(lob)
                        .domainName(domainName)
                        .domainType(domainType)
                        .elementName(element.getName())
                        .env(env)
                        .build();

                fileService.ensureDirectory(elementPath.toElementDir());
                fileService.writeJsonNode(elementPath.toEnvFile(), element.getValue());
            }
        }

        // Write _meta.json
        MetaFile metaFile = config.toMetaFile();
        fileService.writeMetaFile(configPath.toMetaPath(), metaFile);
    }

    private ConfigPath buildConfigPath(DomainConfig config) {
        return ConfigPath.builder()
                .basePath(basePath)
                .lob(config.getLob())
                .domainName(config.getDomainName())
                .domainType(config.getDomainType())
                .build();
    }

    private String buildKey(DomainConfig config) {
        return config.getLob() + "/" + config.getDomainName() + "/" + config.getDomainType();
    }
    public List<ReconstructResult> getReconstructedConfig(String lob,String name,String type){
        return new ReconstructService(fileService).reconstruct(lob,name,type);
    }


}