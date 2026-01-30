package com.salescode.cofman.exception;

import lombok.Getter;

/**
 * Thrown when _meta.json is invalid or corrupted.
 */
@Getter
public class InvalidMetaException extends RuntimeException {
    
    private String metaFilePath;
    
    public InvalidMetaException(String message) {
        super(message);
    }
    
    public InvalidMetaException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public InvalidMetaException(String metaFilePath, String reason) {
        super(String.format("Invalid meta file at %s: %s", metaFilePath, reason));
        this.metaFilePath = metaFilePath;
    }
    
    public InvalidMetaException(String metaFilePath, String reason, Throwable cause) {
        super(String.format("Invalid meta file at %s: %s", metaFilePath, reason), cause);
        this.metaFilePath = metaFilePath;
    }
}
