package vn.danang.polaris.assistant.entity;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import vn.danang.polaris.assistant.dto.DraftItemDto;

@Converter
public class DraftItemListConverter implements AttributeConverter<List<DraftItemDto>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<DraftItemDto> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize draft items to JSON", e);
        }
    }

    @Override
    public List<DraftItemDto> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, new TypeReference<List<DraftItemDto>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize draft items from JSON", e);
        }
    }
}
