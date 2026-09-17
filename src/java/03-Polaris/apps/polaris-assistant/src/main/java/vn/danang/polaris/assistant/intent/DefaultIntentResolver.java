package vn.danang.polaris.assistant.intent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.entity.AssistantMessage;

@Component
public class DefaultIntentResolver implements IntentResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentResolver.class);

    private static final Pattern ORDER_CANCEL_PATTERN = Pattern.compile(
            "\\b(cancel|cancellation|abort)\\b.*\\border\\b|\\border\\b.*\\b(cancel|cancellation)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_PLACE_PATTERN = Pattern.compile(
            "\\b(place\\s+order|buy|purchase)\\b|\\border\\s+\\d+\\s+of\\b|\\border\\s+[A-Z0-9-]+\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_HISTORY_PATTERN = Pattern.compile(
            "\\b(order\\s+history|past\\s+orders|previous\\s+orders|list.*orders|all.*orders|my\\s+orders|previous\\s+purchases)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CUSTOMER_LOOKUP_PATTERN = Pattern.compile(
            "\\b(find|search|lookup|look\\s+up|who\\s+is)\\b.*\\b(customer|client|user)\\b|\\b(customer|client)\\s+(lookup|search|named)\\b|\\b(my\\s+name\\s+is|i\\s+am|i'm)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_STATUS_PATTERN = Pattern.compile(
            "\\b(status\\s+of|where\\s+is|track|tracking)\\b.*\\border\\b|\\border\\b.*\\b(status|track|where)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_DETAILS_PATTERN = Pattern.compile(
            "\\b(full\\s+details|what\\s+did\\s+i\\s+order|order\\s+contents|items\\s+in\\s+order|breakdown)\\b|\\bdetails\\b.*\\border\\b|\\border\\b.*\\bdetails\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SKU_PATTERN = Pattern.compile(
            "\\b[A-Z0-9]{2,}-[A-Z0-9-]+[A-Z0-9]\\b");

    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^\\s*(hello|hi|hey|good\\s+morning|good\\s+afternoon|good\\s+evening|who\\s+are\\s+you|what\\s+can\\s+you\\s+do|help)\\b.*",
            Pattern.CASE_INSENSITIVE);

    private final IntentTaxonomyProperties taxonomy;

    @Autowired
    public DefaultIntentResolver(IntentTaxonomyProperties taxonomy) {
        this.taxonomy = taxonomy != null ? taxonomy : new IntentTaxonomyProperties();
    }

    public DefaultIntentResolver() {
        this(new IntentTaxonomyProperties());
    }

    @Override
    public IntentClassification resolve(String userMessage, List<AssistantMessage> context) {
        if (userMessage == null || userMessage.isBlank()) {
            return new IntentClassification(IntentClassification.GENERAL_CONVERSATION, 1.0);
        }

        String query = userMessage.trim();
        String lowerQuery = query.toLowerCase(Locale.ROOT);

        // 1. Check exact example match across taxonomy
        for (IntentDefinition def : taxonomy.getIntents()) {
            for (String example : def.getExamples()) {
                if (lowerQuery.equalsIgnoreCase(example.trim()) || lowerQuery.contains(example.trim().toLowerCase(Locale.ROOT))) {
                    log.debug("Exact or substring match for intent [{}] from example [{}]", def.getId(), example);
                    return new IntentClassification(def.getId(), Math.max(0.95, def.getConfidenceThreshold()));
                }
            }
        }

        // 2. Specialized Regex & Heuristic Checks
        // 2a. Order Cancellation
        if (ORDER_CANCEL_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.ORDER_CANCEL, 0.95);
        }

        // 2b. Order Placement
        if (ORDER_PLACE_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.ORDER_PLACE, 0.95);
        }

        // 2c. Order History
        if (ORDER_HISTORY_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.ORDER_HISTORY, 0.95);
        }

        // 2c-1. Customer Lookup
        if (CUSTOMER_LOOKUP_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.CUSTOMER_LOOKUP, 0.95);
        }

        // 2d. Order Details vs Status
        if (ORDER_DETAILS_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.ORDER_DETAILS, 0.95);
        }
        if (ORDER_STATUS_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.ORDER_STATUS, 0.95);
        }

        // 2e. SKU Lookup (specific product code identified)
        Matcher skuMatcher = SKU_PATTERN.matcher(query);
        boolean hasSku = false;
        while (skuMatcher.find()) {
            String match = skuMatcher.group();
            // Don't treat order numbers as product SKUs
            if (!match.toUpperCase(Locale.ROOT).startsWith("ORD-")) {
                hasSku = true;
                break;
            }
        }
        if (hasSku && (lowerQuery.contains("sku") || lowerQuery.contains("product") || lowerQuery.contains("tell me") || lowerQuery.contains("stock") || lowerQuery.contains("details"))) {
            return new IntentClassification(IntentClassification.CATALOG_LOOKUP, 0.95);
        }

        // 2f. Product Search / Browse
        if (lowerQuery.contains("search") || lowerQuery.contains("find") || lowerQuery.contains("show me")
                || lowerQuery.contains("browse") || lowerQuery.contains("catalog") || lowerQuery.contains("in stock")
                || lowerQuery.contains("products") || lowerQuery.contains("electronics") || lowerQuery.contains("shoes")
                || lowerQuery.contains("charger") || lowerQuery.contains("under $") || lowerQuery.contains("price")) {
            return new IntentClassification(IntentClassification.CATALOG_SEARCH, 0.92);
        }

        // 2g. Greeting / Conversation
        if (GREETING_PATTERN.matcher(query).find()) {
            return new IntentClassification(IntentClassification.GENERAL_CONVERSATION, 0.95);
        }

        // 3. Fallback: Word token overlap similarity against taxonomy descriptions and examples
        IntentClassification bestFuzzy = scoreFuzzy(lowerQuery);
        if (bestFuzzy != null) {
            return bestFuzzy;
        }

        return new IntentClassification(IntentClassification.GENERAL_CONVERSATION, 0.40);
    }

    private IntentClassification scoreFuzzy(String query) {
        Set<String> queryWords = tokenize(query);
        if (queryWords.isEmpty()) {
            return null;
        }

        String bestIntent = null;
        double maxScore = 0.0;

        for (IntentDefinition def : taxonomy.getIntents()) {
            double score = 0.0;
            // Compare against examples
            for (String ex : def.getExamples()) {
                Set<String> exWords = tokenize(ex.toLowerCase(Locale.ROOT));
                double overlap = intersectionSize(queryWords, exWords);
                double jaccard = overlap / (queryWords.size() + exWords.size() - overlap);
                if (jaccard > score) {
                    score = jaccard;
                }
            }
            // Compare against description
            if (def.getDescription() != null) {
                Set<String> descWords = tokenize(def.getDescription().toLowerCase(Locale.ROOT));
                double overlap = intersectionSize(queryWords, descWords);
                double jaccard = overlap / (queryWords.size() + descWords.size() - overlap);
                if (jaccard * 0.8 > score) {
                    score = jaccard * 0.8;
                }
            }

            if (score > maxScore) {
                maxScore = score;
                bestIntent = def.getId();
            }
        }

        if (bestIntent != null && maxScore > 0.25) {
            double confidence = Math.min(0.88, 0.50 + maxScore);
            return new IntentClassification(bestIntent, confidence);
        }

        return null;
    }

    private Set<String> tokenize(String text) {
        String[] words = text.replaceAll("[^a-zA-Z0-9\\s-]", " ").split("\\s+");
        Set<String> set = new HashSet<>();
        for (String w : words) {
            if (!w.isBlank() && w.length() > 2) {
                set.add(w);
            }
        }
        return set;
    }

    private double intersectionSize(Set<String> s1, Set<String> s2) {
        int count = 0;
        for (String s : s1) {
            if (s2.contains(s)) {
                count++;
            }
        }
        return count;
    }
}
