package com.salescode.cofman.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;

public class JSONArrayConverter implements AttributeConverter<ArrayNode, String>
{
	private static final Logger log = LoggerFactory.getLogger(JSONArrayConverter.class);
	@Override
	public String convertToDatabaseColumn(ArrayNode attribute) {
		if (attribute != null) {
			return attribute.toString();
		} else {
			return null;
		}
	}

	@Override
	public ArrayNode convertToEntityAttribute(String dbData) {
		if (dbData != null) {
			try {
				return (ArrayNode)new ObjectMapper().readTree(dbData);
			} catch (IOException e) {
				log.error("stacktrace", e);
			}
		} 
		return null;
		
	}


}
