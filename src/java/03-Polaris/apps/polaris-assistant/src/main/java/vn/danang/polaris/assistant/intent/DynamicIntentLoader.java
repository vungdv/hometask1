package vn.danang.polaris.assistant.intent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Component responsible for dynamically loading and reloading intent definitions
 * from JSON resources.
 */
@Component
public class DynamicIntentLoader {

    private static final Logger log = LoggerFactory.getLogger(DynamicIntentLoader.class);
    public static final String DEFAULT_INTENT_RESOURCE = "classpath:intents.json";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Autowired
    public DynamicIntentLoader(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.resourceLoader = resourceLoader != null ? resourceLoader : new DefaultResourceLoader();
    }

    public DynamicIntentLoader() {
        this(new ObjectMapper(), new DefaultResourceLoader());
    }

    /**
     * Loads intent definitions from the specified resource location (e.g. classpath:intents.json or file path).
     *
     * @param location the location string of the JSON resource
     * @return unmodifiable list of loaded IntentDefinition objects
     */
    public List<IntentDefinition> loadFromLocation(String location) {
        String targetLocation = (location != null && !location.isBlank()) ? location : DEFAULT_INTENT_RESOURCE;
        try {
            Resource resource = resourceLoader.getResource(targetLocation);
            if (!resource.exists()) {
                log.warn("Intent definitions resource [{}] not found. Falling back to default classpath resource.", targetLocation);
                resource = resourceLoader.getResource(DEFAULT_INTENT_RESOURCE);
            }
            return loadFromResource(resource);
        } catch (Exception e) {
            log.error("Failed to load intent definitions from [{}]: {}", targetLocation, e.getMessage(), e);
            return loadDefaultIntents();
        }
    }

    /**
     * Loads intent definitions from a Spring {@link Resource}.
     *
     * @param resource the resource to read from
     * @return unmodifiable list of loaded IntentDefinition objects
     */
    public List<IntentDefinition> loadFromResource(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("Resource is null or does not exist, falling back to default intents.");
            return loadDefaultIntents();
        }
        try (InputStream is = resource.getInputStream()) {
            return loadFromInputStream(is);
        } catch (IOException e) {
            log.error("Error reading intents from resource [{}]: {}", resource.getDescription(), e.getMessage(), e);
            return loadDefaultIntents();
        }
    }

    /**
     * Loads intent definitions from an {@link InputStream}.
     *
     * @param inputStream the input stream containing JSON content
     * @return unmodifiable list of loaded IntentDefinition objects
     */
    public List<IntentDefinition> loadFromInputStream(InputStream inputStream) {
        if (inputStream == null) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(inputStream);
            return parseJsonNode(root);
        } catch (Exception e) {
            log.error("Error parsing intent definitions JSON: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Loads intent definitions from a raw JSON string.
     *
     * @param jsonContent the JSON string content
     * @return unmodifiable list of loaded IntentDefinition objects
     */
    public List<IntentDefinition> loadFromJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            return parseJsonNode(root);
        } catch (Exception e) {
            log.error("Error parsing intent definitions JSON string: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<IntentDefinition> parseJsonNode(JsonNode root) throws IOException {
        if (root == null) {
            return Collections.emptyList();
        }
        JsonNode intentsArray = root.isArray() ? root : root.get("intents");
        if (intentsArray == null || !intentsArray.isArray()) {
            return Collections.emptyList();
        }
        List<IntentDefinition> list = objectMapper.readValue(
                intentsArray.traverse(objectMapper),
                new TypeReference<List<IntentDefinition>>() {}
        );
        return Collections.unmodifiableList(list != null ? list : Collections.emptyList());
    }

    /**
     * Static utility method to load default intents from the classpath intents.json.
     *
     * @return list of IntentDefinition
     */
    public static List<IntentDefinition> loadDefaultIntents() {
        try (InputStream is = DynamicIntentLoader.class.getClassLoader().getResourceAsStream("intents.json")) {
            if (is != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(is);
                JsonNode intentsArray = root.isArray() ? root : root.get("intents");
                if (intentsArray != null && intentsArray.isArray()) {
                    List<IntentDefinition> list = mapper.readValue(
                            intentsArray.traverse(mapper),
                            new TypeReference<List<IntentDefinition>>() {}
                    );
                    return Collections.unmodifiableList(new ArrayList<>(list));
                }
            }
        } catch (Exception e) {
            log.error("Failed to load default intents from classpath:intents.json: {}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
