package vn.danang.polaris.assistant.observability;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.ObjectProvider;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

class CustomNextSpanAspectTest {

    private Tracer tracer;
    private Span span;
    private Tracer.SpanInScope spanInScope;
    private ProceedingJoinPoint joinPoint;
    private MethodSignature methodSignature;
    private DummySampleService sampleService;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        spanInScope = mock(Tracer.SpanInScope.class);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanInScope);

        joinPoint = mock(ProceedingJoinPoint.class);
        methodSignature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);

        sampleService = new DummySampleService();
        when(joinPoint.getTarget()).thenReturn(sampleService);
    }

    private void setupMethod(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = DummySampleService.class.getMethod(methodName, parameterTypes);
        when(methodSignature.getMethod()).thenReturn(method);
    }

    @Test
    @DisplayName("Should create span with custom name, static tags, and dynamic SpEL argument tags")
    void traceNextSpan_withStaticAndSpELTags_appliesAllTags() throws Throwable {
        setupMethod("annotatedMethod", DummyOrder.class, String.class);
        CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

        DummyOrder order = new DummyOrder("ORD-999", 150.0);
        when(joinPoint.getArgs()).thenReturn(new Object[]{order, "vip-user"});
        when(joinPoint.proceed()).thenReturn("processed");

        Object result = aspect.traceNextSpan(joinPoint);

        assertThat(result).isEqualTo("processed");

        verify(tracer).nextSpan();
        verify(span).name("order.process");
        // Static tag
        verify(span).tag("service.name", "billing");
        // SpEL bound from #order.id
        verify(span).tag("order.id", "ORD-999");
        // SpEL bound from #order.amount
        verify(span).tag("order.amount", "150.0");
        // SpEL ternary with fallback
        verify(span).tag("user.tier", "vip-user");

        verify(span).start();
        verify(tracer).withSpan(span);
        verify(span, times(1)).end();
    }

    @Test
    @DisplayName("Should evaluate SpEL expressions with null-safe navigation and fallback")
    void traceNextSpan_withNullArgs_handlesGracefullyWithoutThrowing() throws Throwable {
        setupMethod("annotatedMethod", DummyOrder.class, String.class);
        CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

        // Null order and null userId
        when(joinPoint.getArgs()).thenReturn(new Object[]{null, null});
        when(joinPoint.proceed()).thenReturn("processed-null");

        Object result = aspect.traceNextSpan(joinPoint);

        assertThat(result).isEqualTo("processed-null");

        verify(span).name("order.process");
        verify(span).tag("service.name", "billing");
        // Null order should not be tagged for order.id
        verify(span, never()).tag(org.mockito.ArgumentMatchers.eq("order.id"), any());
        // Fallback expression evaluated
        verify(span).tag("user.tier", "standard");
        verify(span).end();
    }

    @Test
    @DisplayName("Should extract tags from parameter-level @SpanTag annotations")
    void traceNextSpan_withParameterSpanTags_bindsParameterValues() throws Throwable {
        setupMethod("parameterAnnotatedMethod", String.class, DummyOrder.class);
        CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

        DummyOrder order = new DummyOrder("ORD-456", 75.0);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"client-1", order});
        when(joinPoint.proceed()).thenReturn("done");

        aspect.traceNextSpan(joinPoint);

        verify(span).name("client.order");
        // Parameter with key from value()
        verify(span).tag("client.id", "client-1");
        // Parameter with expression on arg
        verify(span).tag("target.order.id", "ORD-456");
    }

    @Test
    @DisplayName("Should fallback to ClassName.methodName when no span name is specified")
    void traceNextSpan_withDefaultName_fallsBackToMethodName() throws Throwable {
        setupMethod("unnamedSpanMethod");
        CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

        when(joinPoint.getArgs()).thenReturn(new Object[]{});
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.traceNextSpan(joinPoint);

        verify(span).name("DummySampleService.unnamedSpanMethod");
    }

    @Test
    @DisplayName("Should tag error and record exception on span when target method throws")
    void traceNextSpan_whenTargetThrows_recordsErrorAndEndsSpan() throws Throwable {
        setupMethod("unnamedSpanMethod");
        CustomNextSpanAspect aspect = new CustomNextSpanAspect(tracer);

        when(joinPoint.getArgs()).thenReturn(new Object[]{});
        RuntimeException ex = new RuntimeException("Database timeout");
        when(joinPoint.proceed()).thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> aspect.traceNextSpan(joinPoint));
        assertThat(thrown).isSameAs(ex);

        verify(span).error(ex);
        verify(span).tag("error", "true");
        verify(span, times(1)).end();
    }

    @Test
    @DisplayName("Should proceed directly when tracer is absent")
    void traceNextSpan_withoutTracer_proceedsDirectly() throws Throwable {
        setupMethod("unnamedSpanMethod");
        CustomNextSpanAspect aspect = new CustomNextSpanAspect((Tracer) null);

        when(joinPoint.getArgs()).thenReturn(new Object[]{});
        when(joinPoint.proceed()).thenReturn("no-tracer");

        Object result = aspect.traceNextSpan(joinPoint);

        assertThat(result).isEqualTo("no-tracer");
        verify(tracer, never()).nextSpan();
    }

    @Test
    @DisplayName("Should inject tracer from ObjectProvider in constructor")
    @SuppressWarnings("unchecked")
    void constructor_withObjectProvider_extractsTracer() throws Throwable {
        setupMethod("unnamedSpanMethod");
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        CustomNextSpanAspect aspect = new CustomNextSpanAspect(provider);

        when(joinPoint.getArgs()).thenReturn(new Object[]{});
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.traceNextSpan(joinPoint);

        verify(tracer).nextSpan();
    }

    // =========================================================================
    // Dummy Test Classes
    // =========================================================================

    public record DummyOrder(String id, double amount) {}

    public static class DummySampleService {

        @CustomNextSpan(
                name = "order.process",
                tags = {
                    @SpanTag(key = "service.name", value = "billing"),
                    @SpanTag(key = "order.id", expression = "#order?.id()"),
                    @SpanTag(key = "order.amount", expression = "#order?.amount()"),
                    @SpanTag(key = "user.tier", expression = "#userId != null && !#userId.isBlank() ? #userId : 'standard'")
                }
        )
        public String annotatedMethod(DummyOrder order, String userId) {
            return "processed";
        }

        @CustomNextSpan("client.order")
        public String parameterAnnotatedMethod(
                @SpanTag("client.id") String clientId,
                @SpanTag(key = "target.order.id", expression = "id()") DummyOrder order) {
            return "done";
        }

        @CustomNextSpan
        public String unnamedSpanMethod() {
            return "ok";
        }
    }
}
