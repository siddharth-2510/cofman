package com.salescode.cofman.util;

/**
 * Utility to sanitize file and folder names.
 * Matches the Python script's sanitize_file_name() logic exactly.
 */
public class FileNameSanitizer {
    
    /**
     * Sanitize a string for use as a file/folder name.
     * 
     * Logic (matching Python script):
     * 1. Replace invalid characters (\ / : * ? " < > |) with underscore
     * 2. Replace whitespace with underscore
     * 3. Collapse multiple underscores to single underscore
     * 4. Remove leading/trailing underscores
     * 
     * @param name The name to sanitize
     * @return Sanitized name safe for filesystem
     */
    public static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return "unnamed";
        }
        
        // Replace invalid characters: \ / : * ? " < > |
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        
        // Replace whitespace with underscore
        sanitized = sanitized.replaceAll("\\s+", "_");
        
        // Collapse multiple underscores to single
        sanitized = sanitized.replaceAll("_+", "_");
        
        // Remove leading/trailing underscores
        sanitized = sanitized.replaceAll("^_|_$", "");
        
        // If result is empty after sanitization
        if (sanitized.isEmpty()) {
            return "unnamed";
        }
        
        return sanitized;
    }
    
    /**
     * Check if a name needs sanitization
     */
    public static boolean needsSanitization(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        return !name.equals(sanitize(name));
    }
}
