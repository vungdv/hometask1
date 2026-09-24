package vn.danang.polaris.assistant.intent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.text.similarity.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * Standard implementation of {@link IntentResolver} that utilizes Apache Commons Text
 * {@link CosineSimilarity} to match user messages against dynamically loaded intent examples.
 */
@Component
public class DefaultIntentResolver implements IntentResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentResolver.class);

    private final IntentTaxonomyProperties taxonomy;
    private final CosineSimilarity cosineSimilarity = new CosineSimilarity();

    @Autowired
    public DefaultIntentResolver(IntentTaxonomyProperties taxonomy) {
        this.taxonomy = taxonomy != null ? taxonomy : new IntentTaxonomyProperties();
    }

    public DefaultIntentResolver() {
        this(new IntentTaxonomyProperties());
    }

    @Override
    public IntentClassification resolve(String userMessage, List<AssistantMessage> context) {
        String query = userMessage != null ? userMessage.trim() : "";
        if (query.isBlank() && context != null && !context.isEmpty()) {
            for (int i = context.size() - 1; i >= 0; i--) {
                AssistantMessage msg = context.get(i);
                if (msg != null && msg.getRole() == MessageRole.USER && msg.getContent() != null && !msg.getContent().isBlank()) {
                    query = msg.getContent().trim();
                    break;
                }
            }
        }

        if (query.isBlank()) {
            return new IntentClassification(IntentClassification.GENERAL_CONVERSATION, 1.0);
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        Map<CharSequence, Integer> qTokens = toTokens(lowerQuery);

        IntentDefinition bestIntent = null;
        double highestScore = -1.0;

        for (IntentDefinition def : taxonomy.getIntents()) {
            // Guard: order numbers (e.g., ORD-1001) are not product catalog SKUs
            if (lowerQuery.contains("ord-") && IntentClassification.CATALOG_LOOKUP.equals(def.getId())) {
                continue;
            }

            double intentScore = 0.0;
            for (String example : def.getExamples()) {
                if (example == null || example.isBlank()) {
                    continue;
                }
                String lowerEx = example.trim().toLowerCase(Locale.ROOT);
                Map<CharSequence, Integer> exTokens = toTokens(lowerEx);

                double score;
                if (lowerQuery.equals(lowerEx)) {
                    score = 1.0;
                } else if (lowerQuery.contains(lowerEx)) {
                    double cos = 0.0;
                    try {
                        cos = cosineSimilarity.cosineSimilarity(qTokens, exTokens);
                    } catch (Exception ignored) {
                    }
                    score = Math.max(0.95, cos);
                } else {
                    try {
                        score = cosineSimilarity.cosineSimilarity(qTokens, exTokens);
                    } catch (Exception ignored) {
                        score = 0.0;
                    }
                }

                if (score > intentScore) {
                    intentScore = score;
                }
            }

            // Return first highest score matching
            if (intentScore > highestScore) {
                highestScore = intentScore;
                bestIntent = def;
            }
        }

        if (bestIntent != null && highestScore >= 0.50) {
            log.debug("Resolved intent [{}] with confidence [{}] for query [{}]", bestIntent.getId(), highestScore, query);
            return new IntentClassification(bestIntent.getId(), highestScore);
        }

        log.debug("No confident intent found (score: [{}]). Falling back to general.conversation", highestScore);
        return new IntentClassification(IntentClassification.GENERAL_CONVERSATION, Math.max(0.0, highestScore));
    }

    private Map<CharSequence, Integer> toTokens(String text) {
        Map<CharSequence, Integer> map = new HashMap<>();
        String[] words = text.replaceAll("[^a-zA-Z0-9\\s-]", " ").split("\\s+");
        for (String w : words) {
            if (!w.isBlank()) {
                map.merge(w, 1, Integer::sum);
            }
        }
        return map;
    }
}
