package com.salescode.cofman.model.enums;

/**
 * Defines the patterns for parsing JSON elements during deconstruction
 * and how to reconstruct them back to their original form.
 */
public enum ElementPattern {
    
    /**
     * Dict with "name" field - store whole object, return as-is
     * Example: {"name": "visa", "limit": 1000}
     */
    NAME_FIELD,
    
    /**
     * Dict with "id" field - store whole object, return as-is
     * Example: {"id": 123, "type": "card"}
     */
    ID_FIELD,
    
    /**
     * Plain string value - store string, return as-is
     * Example: "visa"
     */
    PLAIN_STRING,
    
    /**
     * Number or boolean - store value, return as-is
     * Example: 123, true, 45.67
     */
    PRIMITIVE,
    
    /**
     * Dict with single key - store inner value, wrap back during reconstruction
     * Example: {"visa": {"limit": 1000}} → stores {"limit": 1000}, reconstructs to {"visa": {"limit": 1000}}
     */
    SINGLE_KEY_OBJECT,
    
    /**
     * Dict with multiple keys where all values are dict/list - explode into multiple entries
     * Requires group field for reconstruction to merge back into single object
     * Example: {"hdfc": {...}, "icici": {...}} → two folders, same group
     */
    MULTI_KEY_EXPLODE,
    
    /**
     * Fallback for anything that doesn't match above patterns
     * Uses item_{index} naming
     */
    FALLBACK
}
