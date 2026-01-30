package com.salescode.cofman.services;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.salescode.cofman.exception.ConfigNotFoundException;
import com.salescode.cofman.exception.ConfigOperationException;
import com.salescode.cofman.exception.InvalidMetaException;
import com.salescode.cofman.model.dto.MetaFile;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Service for file I/O operations.
 * Handles reading/writing JSON files, creating directories, etc.
 */
@Getter
public class ConfigFileService {
    
    private final ObjectMapper objectMapper;
    private final String basePath;
    
    public ConfigFileService(String basePath) {
        this.basePath = basePath;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    
    /**
     * Ensure directory exists, create if necessary
     */
    public void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new ConfigOperationException("ensureDirectory",
                    "Failed to create directory: " + path, e);
        }
    }
    
    /**
     * Write JSON value to file
     */
    public void writeJson(Path filePath, Object value) {
        try {
            ensureDirectory(filePath.getParent());
            objectMapper.writeValue(filePath.toFile(), value);
        } catch (IOException e) {
            throw new ConfigOperationException("writeJson", 
                    "Failed to write JSON to: " + filePath, e);
        }
    }
    
    /**
     * Write JsonNode to file
     */
    public void writeJsonNode(Path filePath, JsonNode value) {
        try {
            ensureDirectory(filePath.getParent());
            objectMapper.writeValue(filePath.toFile(), value);
        } catch (IOException e) {
            throw new ConfigOperationException("writeJsonNode", 
                    "Failed to write JSON to: " + filePath, e);
        }
    }
    
    /**
     * Read JSON file as JsonNode
     */
    public JsonNode readJsonNode(Path filePath) {
        if (!Files.exists(filePath)) {
            throw new ConfigNotFoundException("File not found: " + filePath);
        }
        try {
            return objectMapper.readTree(filePath.toFile());
        } catch (IOException e) {
            throw new ConfigOperationException("readJsonNode", 
                    "Failed to read JSON from: " + filePath, e);
        }
    }
    
    /**
     * Read MetaFile from _meta.json
     */
    public MetaFile readMetaFile(Path metaFilePath) {
        if (!Files.exists(metaFilePath)) {
            throw new ConfigNotFoundException("Meta file not found: " + metaFilePath);
        }
        try {
            return objectMapper.readValue(metaFilePath.toFile(), MetaFile.class);
        } catch (IOException e) {
            throw new InvalidMetaException(metaFilePath.toString(),
                    "Failed to parse meta file", e);
        }
    }
    
    /**
     * Write MetaFile to _meta.json
     */
    public void writeMetaFile(Path metaFilePath, MetaFile metaFile) {
        writeJson(metaFilePath, metaFile);
    }
    
    /**
     * Check if file exists
     */
    public boolean fileExists(Path path) {
        return Files.exists(path);
    }
    
    /**
     * Check if directory exists
     */
    public boolean directoryExists(Path path) {
        return Files.exists(path) && Files.isDirectory(path);
    }
    
    /**
     * Delete file
     */
    public void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new ConfigOperationException("deleteFile", 
                    "Failed to delete file: " + path, e);
        }
    }
    
    /**
     * Delete directory recursively
     */
    public void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted((a, b) -> -a.compareTo(b)) // Reverse order for deletion
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new ConfigOperationException("deleteDirectory", 
                                    "Failed to delete: " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new ConfigOperationException("deleteDirectory", 
                    "Failed to walk directory: " + path, e);
        }
    }
    
    /**
     * Copy file
     */
    public void copyFile(Path source, Path target) {
        try {
            ensureDirectory(target.getParent());
            Files.copy(source, target);
        } catch (IOException e) {
            throw new ConfigOperationException("copyFile", 
                    "Failed to copy from " + source + " to " + target, e);
        }
    }
    
    /**
     * Copy directory recursively
     */
    public void copyDirectory(Path source, Path target) {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(sourcePath -> {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                try {
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (IOException e) {
                    throw new ConfigOperationException("copyDirectory", 
                            "Failed to copy: " + sourcePath, e);
                }
            });
        } catch (IOException e) {
            throw new ConfigOperationException("copyDirectory", 
                    "Failed to walk directory: " + source, e);
        }
    }
    
    /**
     * List subdirectories in a directory
     */
    public Stream<Path> listSubdirectories(Path path) {
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return Stream.empty();
        }
        try {
            return Files.list(path).filter(Files::isDirectory);
        } catch (IOException e) {
            throw new ConfigOperationException("listSubdirectories", 
                    "Failed to list directories in: " + path, e);
        }
    }
}
