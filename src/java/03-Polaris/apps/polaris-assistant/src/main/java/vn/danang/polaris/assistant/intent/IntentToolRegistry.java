package vn.danang.polaris.assistant.intent;

import java.util.Collection;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Legacy registry class retained for backwards compatibility.
 * All intent resolution, taxonomy management, and tool filtering are consolidated into {@link DefaultIntentResolver}.
 *
 * @deprecated Use {@link DefaultIntentResolver} directly.
 */
@Deprecated
@Component
public class IntentToolRegistry extends DefaultIntentResolver {

    @Autowired
    public IntentToolRegistry() {
        super();
    }

    public IntentToolRegistry(Collection<IntentDefinition> intents) {
        super(intents);
    }
}
