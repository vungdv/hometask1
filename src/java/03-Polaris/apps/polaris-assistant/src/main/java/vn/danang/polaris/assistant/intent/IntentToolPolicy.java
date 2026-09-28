package vn.danang.polaris.assistant.intent;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives tool-level policy from the intent taxonomy ({@code intents.json}), so authorization no
 * longer depends on which intent the classifier picked:
 * <ul>
 *   <li>{@link #readOnlyTools} — tools declared by at least one non-mutating intent. This is the
 *       only set a low-confidence turn may expose to the model.</li>
 *   <li>{@link #requiredScopes} — the scope(s) a tool needs, whatever intent is active. A tool
 *       declared by a non-mutating intent takes its scope from the non-mutating declarers only
 *       (mutating intents that merely reuse a read tool don't raise its bar); otherwise from the
 *       mutating declarers. Several distinct scopes are all required (fail closed).</li>
 * </ul>
 */
public final class IntentToolPolicy {

    private IntentToolPolicy() {
    }

    /**
     * @return names of tools declared by at least one intent with {@code mutating=false}
     */
    public static Set<String> readOnlyTools(Collection<IntentDefinition> intents) {
        Set<String> tools = new LinkedHashSet<>();
        if (intents == null) {
            return tools;
        }
        intents.stream()
                .filter(def -> def != null && !def.mutating())
                .forEach(def -> tools.addAll(def.allowedTools()));
        return tools;
    }

    /**
     * @return the non-blank scopes required to call {@code toolName}; empty if no intent declares
     *         the tool or none of its declarers names a scope
     */
    public static Set<String> requiredScopes(String toolName, Collection<IntentDefinition> intents) {
        if (toolName == null || intents == null) {
            return Set.of();
        }
        List<IntentDefinition> declarers = intents.stream()
                .filter(def -> def != null && def.allowedTools().contains(toolName))
                .toList();
        boolean readOnly = declarers.stream().anyMatch(def -> !def.mutating());

        Set<String> scopes = new LinkedHashSet<>();
        declarers.stream()
                .filter(def -> readOnly ? !def.mutating() : def.mutating())
                .map(IntentDefinition::requiredScope)
                .filter(scope -> scope != null && !scope.isBlank())
                .forEach(scopes::add);
        return scopes;
    }
}
