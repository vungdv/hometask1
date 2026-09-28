package vn.danang.polaris.order.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the Keycloak realm contract that shopper identity binding (V12, CustomerService) depends on.
 */
@DisplayName("Keycloak realm-export contract")
class KeycloakRealmExportTest {

    /** Module working directory is apps/polaris; the realm lives at the project root. */
    private static final Path REALM_EXPORT = Path.of("../../docker/keycloak/realm-export.json");

    private static JsonNode realm() throws Exception {
        assertThat(REALM_EXPORT).exists();
        return new ObjectMapper().readTree(Files.readString(REALM_EXPORT));
    }

    @Test
    @DisplayName("token-issuing clients include the 'basic' scope so access tokens carry the 'sub' claim (Keycloak 25+)")
    void tokenClients_includeBasicScope() throws Exception {
        Map<String, JsonNode> clients = new HashMap<>();
        realm().get("clients").forEach(client -> clients.put(client.get("clientId").asText(), client));

        for (String clientId : List.of("polaris-app", "polaris-local", "polaris-mcp")) {
            assertThat(clients).containsKey(clientId);
            List<String> scopes = StreamSupport.stream(clients.get(clientId).get("defaultClientScopes").spliterator(), false)
                    .map(JsonNode::asText)
                    .toList();
            assertThat(scopes).as(clientId + " defaultClientScopes").contains("basic", "email");
        }
    }

    @Test
    @DisplayName("shopper users have the fixed IDs that V12 backfills into customers.auth_subject")
    void shopperUsers_matchV12Backfill() throws Exception {
        Map<String, String> idsByEmail = new HashMap<>();
        realm().get("users").forEach(user -> {
            if (user.has("id")) {
                idsByEmail.put(user.get("email").asText(), user.get("id").asText());
            }
        });
        String migration = Files.readString(Path.of(
                "../../libs/polaris-common/src/main/resources/db/migration/V12__add_customer_auth_subject.sql"));

        for (String email : List.of("alice.tran@example.com", "ben.nguyen@example.com", "chi.le@example.com")) {
            assertThat(idsByEmail).containsKey(email);
            assertThat(migration).contains("auth_subject = '" + idsByEmail.get(email) + "' WHERE email = '" + email + "'");
        }
    }
}
