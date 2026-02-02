package com.salescode.cofman.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salescode.cofman.model.dto.ConfigElement;
import com.salescode.cofman.model.enums.ElementPattern;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses JSON elements to determine their pattern and extract name/value pairs.
 * Matches the Python script's parse_element() logic exactly.
 */
public class ElementParser {
    
    private static final String ITEM_PREFIX = "item_";
    
    /**
     * Parse a JSON element and return list of ConfigElements.
     * Most patterns return a single element, but MULTI_KEY_EXPLODE returns multiple.
     * 
     * @param element The JSON element to parse
     * @param fallbackIndex Current fallback index for unnamed elements
     * @param groupId Group ID for MULTI_KEY_EXPLODE pattern (null for others)
     * @return List of parsed ConfigElements with name, pattern, group, and value
     */
    public static List<ConfigElement> parseElement(JsonNode element, int fallbackIndex, String groupId) {
        List<ConfigElement> parsed = new ArrayList<>();
        
        // Pattern 1: dict with "name" field
        if (element.isObject() && element.has("name") && element.get("name").isTextual()) {
            String name = element.get("name").asText().trim();
            if (!name.isEmpty()) {
                parsed.add(new ConfigElement(
                        FileNameSanitizer.sanitize(name),
                        ElementPattern.NAME_FIELD,
                        null,
                        element
                ));
                return parsed;
            }
        }
        
        // Pattern 2: dict with "id" field
        if (element.isObject() && element.has("id")) {
            String id = element.get("id").asText();
            parsed.add(new ConfigElement(
                    FileNameSanitizer.sanitize(id),
                    ElementPattern.ID_FIELD,
                    null,
                    element
            ));
            return parsed;
        }


        if (element.isObject() && element.has("type")) {
            String type = element.get("type").asText();
            parsed.add(new ConfigElement(
                    FileNameSanitizer.sanitize(type),
                    ElementPattern.TYPE_FIELD,
                    null,
                    element
            ));
            return parsed;
        }
        
        // Pattern 3: plain string
        if (element.isTextual()) {
            String text = element.asText().trim();
            if (!text.isEmpty()) {
                parsed.add(new ConfigElement(
                        FileNameSanitizer.sanitize(text),
                        ElementPattern.PLAIN_STRING,
                        null,
                        element
                ));
                return parsed;
            }
        }
        
        // Pattern 4: number or boolean
        if (element.isNumber() || element.isBoolean()) {
            parsed.add(new ConfigElement(
                    element.asText(),
                    ElementPattern.PRIMITIVE,
                    null,
                    element
            ));
            return parsed;
        }
        
        // Pattern 5: single-key object
        if (element.isObject() && element.size() == 1) {
            String key = element.fieldNames().next();
            JsonNode value = element.get(key);
            parsed.add(new ConfigElement(
                    FileNameSanitizer.sanitize(key),
                    ElementPattern.SINGLE_KEY_OBJECT,
                    null,
                    value  // Store the inner value, not the wrapper
            ));
            return parsed;
        }
        
        // Pattern 6: multi-key object where all values are dict/list - explode
        if (element.isObject() && element.size() > 1) {
            if (allValuesAreContainers(element)) {
                // Generate group ID for this exploded object
                String explodeGroup = groupId != null ? groupId : "group_" + System.currentTimeMillis();
                
                Iterator<Map.Entry<String, JsonNode>> fields = element.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    parsed.add(new ConfigElement(
                            FileNameSanitizer.sanitize(field.getKey()),
                            ElementPattern.MULTI_KEY_EXPLODE,
                            explodeGroup,
                            field.getValue()
                    ));
                }
                return parsed;
            }
        }
        
        // Pattern 7: fallback
        parsed.add(new ConfigElement(
                ITEM_PREFIX + fallbackIndex,
                ElementPattern.FALLBACK,
                null,
                element
        ));
        return parsed;
    }
    
    /**
     * Convenience method without groupId
     */
    public static List<ConfigElement> parseElement(JsonNode element, int fallbackIndex) {
        return parseElement(element, fallbackIndex, null);
    }
    
    /**
     * Check if all values in an object are containers (dict or list)
     */
    private static boolean allValuesAreContainers(JsonNode element) {
        if (!element.isObject()) {
            return false;
        }
        
        Iterator<JsonNode> values = element.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (!value.isObject() && !value.isArray()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Determine the pattern of an element without parsing fully.
     * Useful for validation and preview.
     */
    public static ElementPattern detectPattern(JsonNode element) {
        if (element.isObject() && element.has("name") && element.get("name").isTextual()) {
            String name = element.get("name").asText().trim();
            if (!name.isEmpty()) {
                return ElementPattern.NAME_FIELD;
            }
        }
        
        if (element.isObject() && element.has("id")) {
            return ElementPattern.ID_FIELD;
        }

        if (element.isObject() && element.has("type")) {
            return ElementPattern.TYPE_FIELD;
        }
        
        if (element.isTextual() && !element.asText().trim().isEmpty()) {
            return ElementPattern.PLAIN_STRING;
        }
        
        if (element.isNumber() || element.isBoolean()) {
            return ElementPattern.PRIMITIVE;
        }
        
        if (element.isObject() && element.size() == 1) {
            return ElementPattern.SINGLE_KEY_OBJECT;
        }
        
        if (element.isObject() && element.size() > 1 && allValuesAreContainers(element)) {
            return ElementPattern.MULTI_KEY_EXPLODE;
        }
        
        return ElementPattern.FALLBACK;
    }
}
