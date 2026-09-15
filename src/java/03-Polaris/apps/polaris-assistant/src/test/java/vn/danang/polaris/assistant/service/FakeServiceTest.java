package vn.danang.polaris.assistant.service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.mcp.PolarisMcpClient;
import vn.danang.polaris.assistant.mcp.PolarisMcpProperties;
import vn.danang.polaris.assistant.security.UserContext;

@ExtendWith(MockitoExtension.class)
class FakeServiceTest {

    @Mock
    private PolarisMcpClient mcpClient;

    @Mock
    private UserContext userContext;

    private PolarisMcpProperties properties;
    private ObjectMapper objectMapper;
    private FakeService fakeService;

    @BeforeEach
    void setUp() {
        properties = new PolarisMcpProperties();
        properties.getCore().setUrl("http://localhost:8080/mcp");
        properties.getCore().setTimeoutSeconds(1);
        objectMapper = new ObjectMapper();
        fakeService = new FakeService(mcpClient, userContext, properties, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDemonstrateIssue1ThreadAffinity() {
        Jwt jwt = Jwt.withTokenValue("ey-alice-test-jwt")
                .header("alg", "none")
                .claim("sub", "alice")
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);

        when(userContext.resolveBearerToken()).thenReturn("ey-alice-test-jwt");

        Map<String, Object> result = fakeService.demonstrateIssue1ThreadAffinity();

        assertNotNull(result);
        assertTrue(result.containsKey("issue"));
        assertTrue(result.containsKey("callerThread"));
        assertTrue(result.containsKey("asyncThreadHop"));
        assertTrue(result.containsKey("virtualThreadHop"));
        assertTrue(result.containsKey("remediationWithContextPropagation"));
    }

    @Test
    void testDemonstrateIssue1_withRealUserContext_provesThreadAffinityLoss() throws Exception {
        PolarisMcpProperties realProps = new PolarisMcpProperties();
        realProps.getCore().setAuthToken(null); // Ensure no static fallback masks the issue
        UserContext realUserContext = new UserContext(realProps);

        Jwt jwt = Jwt.withTokenValue("ey-alice-jwt-12345")
                .header("alg", "none")
                .claim("sub", "alice")
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);

        // 1. On caller thread: token resolves correctly
        assertEquals("ey-alice-jwt-12345", realUserContext.resolveBearerToken());

        // 2. On standard CompletableFuture async thread: SecurityContext is lost, returns null
        CompletableFuture<String> asyncHop = CompletableFuture.supplyAsync(realUserContext::resolveBearerToken);
        String asyncToken = asyncHop.get(2, TimeUnit.SECONDS);
        assertNull(asyncToken, "Thread hop must cause UserContext to lose thread-affined token");

        // 3. On new virtual thread: SecurityContext is lost, returns null
        try (ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> vtFuture = vtExecutor.submit(realUserContext::resolveBearerToken);
            String vtToken = vtFuture.get(2, TimeUnit.SECONDS);
            assertNull(vtToken, "Virtual thread hop must cause UserContext to lose thread-affined token");
        }

        // 4. Remediation: DelegatingSecurityContextExecutorService explicitly propagates context
        ExecutorService base = Executors.newVirtualThreadPerTaskExecutor();
        DelegatingSecurityContextExecutorService delegating =
                new DelegatingSecurityContextExecutorService(base, SecurityContextHolder.getContext());
        Future<String> remediatedFuture = delegating.submit(realUserContext::resolveBearerToken);
        String remediatedToken = remediatedFuture.get(2, TimeUnit.SECONDS);
        assertEquals("ey-alice-jwt-12345", remediatedToken, "Context propagation must preserve token");
        delegating.shutdown();
        base.shutdown();
    }

    @Test
    void testDemonstrateIssue2VirtualThreads() {
        when(mcpClient.listAvailableTools()).thenReturn(Collections.emptyList());

        Map<String, Object> result = fakeService.demonstrateIssue2VirtualThreads();

        assertNotNull(result);
        assertTrue(result.containsKey("issue"));
        assertTrue(result.containsKey("jdkVersion"));
        assertEquals(5, result.get("concurrentVirtualThreads"));
    }

    @Test
    void testDemonstrateIssue3StaticId() {
        Map<String, Object> result = fakeService.demonstrateIssue3StaticId();

        assertNotNull(result);
        assertTrue(result.containsKey("issue"));
        assertTrue(result.containsKey("toolsListId"));
        assertTrue(result.containsKey("toolsCallId"));
    }

    @Test
    void testDemonstrateIssue4BroadCatch() {
        Map<String, Object> result = fakeService.demonstrateIssue4BroadCatch();

        assertNotNull(result);
        assertTrue(result.containsKey("issue"));
        assertTrue(result.containsKey("listAvailableToolsResult"));
        assertTrue(result.containsKey("callToolResult"));
    }

    @Test
    void testDemonstrateAll() {
        when(mcpClient.listAvailableTools()).thenReturn(Collections.emptyList());

        Map<String, Object> result = fakeService.demonstrateAll();

        assertNotNull(result);
        assertEquals("SUCCESS", result.get("status"));
        assertTrue(result.containsKey("issue1_threadAffinity"));
        assertTrue(result.containsKey("issue2_virtualThreads"));
        assertTrue(result.containsKey("issue3_staticId"));
        assertTrue(result.containsKey("issue4_broadCatch"));
    }
}
