package com.salescode.cofman.exception;

import lombok.Getter;

/**
 * Thrown when a requested configuration is not found.
 */
@Getter
public class ConfigNotFoundException extends RuntimeException {
    
    private String domainName;
    private String domainType;
    private String elementName;
    
    public ConfigNotFoundException(String message) {
        super(message);
    }
    
    public ConfigNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ConfigNotFoundException(String domainName, String domainType) {
        super(String.format("Configuration not found: domain_name=%s, domain_type=%s", 
                domainName, domainType));
        this.domainName = domainName;
        this.domainType = domainType;
    }
    
    public ConfigNotFoundException(String domainName, String domainType, String elementName) {
        super(String.format("Configuration element not found: domain_name=%s, domain_type=%s, element=%s", 
                domainName, domainType, elementName));
        this.domainName = domainName;
        this.domainType = domainType;
        this.elementName = elementName;
    }
}
