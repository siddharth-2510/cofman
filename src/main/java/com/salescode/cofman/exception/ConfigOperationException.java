package com.salescode.cofman.exception;

import lombok.Getter;

/**
 * Thrown when a configuration operation fails.
 */
@Getter
public class ConfigOperationException extends RuntimeException {
    
    private String operation;
    
    public ConfigOperationException(String message) {
        super(message);
    }
    
    public ConfigOperationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ConfigOperationException(String operation, String message) {
        super(String.format("Config operation '%s' failed: %s", operation, message));
        this.operation = operation;
    }
    
    public ConfigOperationException(String operation, String message, Throwable cause) {
        super(String.format("Config operation '%s' failed: %s", operation, message), cause);
        this.operation = operation;
    }
}
