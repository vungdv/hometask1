package vn.danang.polaris.assistant.observability;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArgumentSanitizerTest {

    @Test
    @DisplayName("Should redact sensitive keys including credit card, cvv, password, address, and email")
    void sanitize_redactsSensitiveFields() {
        Map<String, Object> arguments = Map.of(
                "cardNumber", "4111222233334444",
                "cvv", "123",
                "password", "secretPassword",
                "email", "user@example.com",
                "address", "123 Main St, Da Nang",
                "sku", "CHARGER-65W"
        );

        Map<String, Object> sanitized = ArgumentSanitizer.sanitize(arguments);

        assertThat(sanitized.get("cardNumber")).isEqualTo(ArgumentSanitizer.REDACTED);
        assertThat(sanitized.get("cvv")).isEqualTo(ArgumentSanitizer.REDACTED);
        assertThat(sanitized.get("password")).isEqualTo(ArgumentSanitizer.REDACTED);
        assertThat(sanitized.get("email")).isEqualTo(ArgumentSanitizer.REDACTED);
        assertThat(sanitized.get("address")).isEqualTo(ArgumentSanitizer.REDACTED);
        assertThat(sanitized.get("sku")).isEqualTo("CHARGER-65W");
    }

    @Test
    @DisplayName("Should preserve non-sensitive fields intact")
    void sanitize_preservesNonSensitiveFields() {
        Map<String, Object> arguments = Map.of(
                "query", "wireless headphones",
                "category", "electronics",
                "limit", 10,
                "inStockOnly", true
        );

        Map<String, Object> sanitized = ArgumentSanitizer.sanitize(arguments);

        assertThat(sanitized).isEqualTo(arguments);
    }

    @Test
    @DisplayName("Should recursively sanitize nested maps and lists")
    void sanitize_handlesNestedMapsAndLists() {
        Map<String, Object> paymentInfo = Map.of("cardNumber", "4111222233334444", "method", "CREDIT_CARD");
        Map<String, Object> recipient = Map.of("name", "Nguyen Van A", "phone", "+84901234567");
        Map<String, Object> arguments = Map.of(
                "orderId", "ORD-999",
                "payment", paymentInfo,
                "recipients", List.of(recipient)
        );

        Map<String, Object> sanitized = ArgumentSanitizer.sanitize(arguments);

        assertThat(sanitized.get("orderId")).isEqualTo("ORD-999");

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedPayment = (Map<String, Object>) sanitized.get("payment");
        assertThat(sanitizedPayment.get("cardNumber")).isEqualTo(ArgumentSanitizer.REDACTED);
        assertThat(sanitizedPayment.get("method")).isEqualTo("CREDIT_CARD");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sanitizedRecipients = (List<Map<String, Object>>) sanitized.get("recipients");
        assertThat(sanitizedRecipients.get(0).get("name")).isEqualTo("Nguyen Van A");
        assertThat(sanitizedRecipients.get(0).get("phone")).isEqualTo(ArgumentSanitizer.REDACTED);
    }

    @Test
    @DisplayName("Should bound summary length strictly to maximum allowed characters")
    void sanitizeToSummary_boundsLength() {
        String longQuery = "a".repeat(300);
        Map<String, Object> arguments = Map.of("query", longQuery);

        String summary = ArgumentSanitizer.sanitizeToSummary(arguments, 256);

        assertThat(summary.length()).isLessThanOrEqualTo(256);
        assertThat(summary).endsWith("...");
    }

    @Test
    @DisplayName("Should return empty JSON object on null or empty input without throwing exception")
    void sanitizeToSummary_handlesNullAndEmpty() {
        assertThat(ArgumentSanitizer.sanitizeToSummary(null)).isEqualTo("{}");
        assertThat(ArgumentSanitizer.sanitizeToSummary(Map.of())).isEqualTo("{}");
        assertThat(ArgumentSanitizer.sanitize(null)).isEmpty();
        assertThat(ArgumentSanitizer.sanitize(Map.of())).isEmpty();
    }
}
