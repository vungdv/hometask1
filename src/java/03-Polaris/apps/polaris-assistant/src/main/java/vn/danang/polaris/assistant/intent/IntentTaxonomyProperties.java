package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "polaris.assistant.intent")
public class IntentTaxonomyProperties {

    private String file = DynamicIntentLoader.DEFAULT_INTENT_RESOURCE;
    private List<IntentDefinition> intents = new ArrayList<>();

    @Autowired(required = false)
    private DynamicIntentLoader dynamicIntentLoader;

    public IntentTaxonomyProperties() {
        this.intents = createDefaultIntents();
    }

    public IntentTaxonomyProperties(DynamicIntentLoader dynamicIntentLoader) {
        this.dynamicIntentLoader = dynamicIntentLoader;
        reload();
    }

    @PostConstruct
    public void init() {
        if (this.intents == null || this.intents.isEmpty()) {
            reload();
        }
    }

    public void reload() {
        DynamicIntentLoader loader = this.dynamicIntentLoader != null
                ? this.dynamicIntentLoader
                : new DynamicIntentLoader();
        List<IntentDefinition> loaded = loader.loadFromLocation(this.file);
        if (loaded != null && !loaded.isEmpty()) {
            this.intents = new ArrayList<>(loaded);
        } else if (this.intents == null || this.intents.isEmpty()) {
            this.intents = createDefaultIntents();
        }
    }

    public void setIntents(List<IntentDefinition> intents) {
        if (intents != null && !intents.isEmpty()) {
            this.intents = intents;
        }
    }

    public static List<IntentDefinition> createDefaultIntents() {
        List<IntentDefinition> loaded = DynamicIntentLoader.loadDefaultIntents();
        return loaded != null && !loaded.isEmpty() ? new ArrayList<>(loaded) : new ArrayList<>();
    }
}
