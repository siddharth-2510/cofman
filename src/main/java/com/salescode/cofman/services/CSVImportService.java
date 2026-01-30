package com.salescode.cofman.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salescode.cofman.model.dto.MetaElement;
import com.salescode.cofman.model.dto.MetaFile;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Simple CSV import service.
 * Filename format: {lob}_{env}.csv (e.g., niine_uat.csv)
 */
@Slf4j
public class CSVImportService {

    private  ConfigFileService fileService;
    private ObjectMapper objectMapper;

    public CSVImportService(String basePath) {
        this.fileService = new ConfigFileService(basePath);
        this.objectMapper = fileService.getObjectMapper();
    }

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

        DeconstructService deconstructService = new DeconstructService(fileService, lob, env);

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
                    JsonNode jsonArray = objectMapper.readTree(domainValues);

                    // Check if meta already exists - if so, just add env files
                    Path metaPath = Paths.get(fileService.getBasePath(), lob, domainName, domainType, "_meta.json");

                    if (Files.exists(metaPath)) {
                        // Domain exists - add env files only
                        addEnvFiles(lob, env, domainName, domainType, jsonArray);
                        log.info("Added env files: {}/{} ({})", domainName, domainType, env);
                    } else {
                        // New domain - use deconstruct
                        deconstructService.deconstruct(lob, domainName, domainType, jsonArray);
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
        Path metaPath = Paths.get(fileService.getBasePath(), lob, domainName, domainType, "_meta.json");
        MetaFile metaFile = fileService.readMetaFile(metaPath);

        int index = 0;
        for (JsonNode element : jsonArray) {
            if (index >= metaFile.getElements().size()) break;

            MetaElement metaElement = metaFile.getElements().get(index);
            Path envFile = Paths.get(fileService.getBasePath(), lob, domainName, domainType,
                    metaElement.getName(), env + ".json");

            if (!Files.exists(envFile)) {
                fileService.writeJsonNode(envFile, element);
            }
            index++;
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
}