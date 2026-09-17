package vn.danang.polaris.assistant.observability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility for sanitizing tool arguments prior to trace span tagging.
 * Redacts sensitive PII and PCI fields and generates length-bounded summaries.
 */
public final class ArgumentSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ArgumentSanitizer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String REDACTED = "[REDACTED]";
    public static final int DEFAULT_MAX_SUMMARY_LENGTH = 256;

    private static final Set<String> SENSITIVE_KEY_PATTERNS = Set.of(
            "password", "token", "secret", "authorization", "auth",
            "card", "cardnumber", "cvv", "pan", "account",
            "ssn", "tax_id",
            "email", "phone", "mobile",
            "address", "street", "postal", "zip",
            "customer_id", "user_id"
    );

    private ArgumentSanitizer() {}

    public static Map<String, Object> sanitize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        return sanitizeMap(arguments);
    }

    public static String sanitizeToSummary(Map<String, Object> arguments) {
        return sanitizeToSummary(arguments, DEFAULT_MAX_SUMMARY_LENGTH);
    }

    public static String sanitizeToSummary(Map<String, Object> arguments, int maxLength) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> sanitized = sanitizeMap(arguments);
            String json = OBJECT_MAPPER.writeValueAsString(sanitized);
            if (json.length() > maxLength) {
                return json.substring(0, maxLength - 3) + "...";
            }
            return json;
        } catch (Exception e) {
            log.warn("Failed to serialize sanitized arguments summary: {}", e.getMessage());
            return "{\"sanitization_error\":\"true\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeMap(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (isSensitiveKey(key)) {
                result.put(key, REDACTED);
            } else if (value instanceof Map<?, ?> nestedMap) {
                result.put(key, sanitizeMap((Map<String, Object>) nestedMap));
            } else if (value instanceof List<?> list) {
                result.put(key, sanitizeList(list));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sanitizeList(List<?> list) {
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> nestedMap) {
                result.add(sanitizeMap((Map<String, Object>) nestedMap));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String pattern : SENSITIVE_KEY_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
