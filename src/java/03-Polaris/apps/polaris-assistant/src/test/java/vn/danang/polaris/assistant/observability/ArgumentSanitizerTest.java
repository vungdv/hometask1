package vn.danang.polaris.assistant.observability;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ArgumentSanitizerTest {

    // =========================================================================
    // 1. Happy path — primary redaction and preservation
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given arguments with sensitive keys, when sanitized, then replaces sensitive values with [REDACTED]")
        void redacts_sensitive_keys_including_card_cvv_password_address_email() {
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
        @DisplayName("Given arguments with non-sensitive fields, when sanitized, then preserves original values intact")
        void preserves_non_sensitive_fields_intact() {
            Map<String, Object> arguments = Map.of(
                    "query", "wireless headphones",
                    "category", "electronics",
                    "limit", 10,
                    "inStockOnly", true
            );

            Map<String, Object> sanitized = ArgumentSanitizer.sanitize(arguments);

            assertThat(sanitized).isEqualTo(arguments);
        }
    }

    // =========================================================================
    // 2. Invalid input & null handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & null handling")
    class InvalidInput {

        @Test
        @DisplayName("Given null arguments map, when sanitized, then returns empty map without throwing exception")
        void handles_null_arguments_gracefully() {
            assertThat(ArgumentSanitizer.sanitize(null)).isEmpty();
            assertThat(ArgumentSanitizer.sanitizeToSummary(null)).isEqualTo("{}");
        }

        @Test
        @DisplayName("Given empty arguments map, when sanitized, then returns empty map")
        void handles_empty_arguments_gracefully() {
            assertThat(ArgumentSanitizer.sanitize(Map.of())).isEmpty();
            assertThat(ArgumentSanitizer.sanitizeToSummary(Map.of())).isEqualTo("{}");
        }
    }

    // =========================================================================
    // 3. Edge cases — nested collections, character limits
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given nested maps and lists of maps, when sanitized, then recursively redacts nested sensitive fields")
        void recursively_sanitizes_nested_maps_and_lists() {
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
        @DisplayName("Given payload exceeding maximum length, when converted to summary, then bounds strictly and appends ellipsis")
        void bounds_summary_length_strictly_with_ellipsis_truncation() {
            String longQuery = "a".repeat(300);
            Map<String, Object> arguments = Map.of("query", longQuery);

            String summary = ArgumentSanitizer.sanitizeToSummary(arguments, 256);

            assertThat(summary.length()).isLessThanOrEqualTo(256);
            assertThat(summary).endsWith("...");
        }
    }
}
