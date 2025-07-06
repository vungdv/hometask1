using app.Telemetry;
using Polly;
using Polly.Extensions.Http;

namespace app.PolyPolicies;

public static class AppPoliciesExtensions
{
    private static IAsyncPolicy<HttpResponseMessage>? _policy;
    public static readonly Action<ILogger, string?, int, TimeSpan, Exception?> _preCompiledLogMessage =
        LoggerMessage.Define<string?, int, TimeSpan>(
            logLevel: LogLevel.Warning,
            eventId: 102,
            formatString: "Retrying for {uri}, at {retryAttempt} times, after {timespan}");

    /// <summary>
    /// Objective: based on the request, select a policy to apply. 
    /// The first version should provide a circuit breaker and a retry policy by default.
    /// We could improve this by defining strategies for different endpoints or services.
    /// This is a simple example of solving the stability anti-patterns mentioned in the
    /// <see href="~/docs/stability-anti-patterns/readme.md">stability-anti-patterns</see>.
    /// </summary>
    /// <param name="serviceProvider"></param>
    /// <param name="request"></param>
    /// <returns></returns>
    public static IAsyncPolicy<HttpResponseMessage> SelectPolicy(IServiceProvider serviceProvider, HttpRequestMessage request)
    {
        _policy ??= CreateCircuitWithRetry(serviceProvider, request);
        return _policy;
    }

    private static Polly.Wrap.AsyncPolicyWrap<HttpResponseMessage> CreateCircuitWithRetry(IServiceProvider serviceProvider,
                                                                                          HttpRequestMessage request)
    {
        var logger = serviceProvider.GetRequiredService<ILoggerFactory>().CreateLogger("RetryPolicy");
        return CreateRetryPolicy(request, logger)
             .WrapAsync(CreateCircuitBreakerPolicy(request, logger));
    }

    /// <summary>
    /// Retry policy doesn't handle timeouts, so it respects the default timeout of the HttpClient.
    /// </summary>
    /// <param name="request"></param>
    /// <param name="logger"></param>
    /// <returns></returns>
    private static Polly.Retry.AsyncRetryPolicy<HttpResponseMessage> CreateRetryPolicy(HttpRequestMessage request, ILogger logger)
     => HttpPolicyExtensions
        .HandleTransientHttpError()
        .WaitAndRetryAsync(
             retryCount: 3,
             sleepDurationProvider: retryAttempt => TimeSpan.FromSeconds(Math.Pow(2, retryAttempt - 1)), // 1s, 2s, 4s, 8s
             onRetry: (outcome, timespan, retryAttempt, context) =>
             {
                 Meters.UserServiceRetryCounter.Add(1, new KeyValuePair<string, object?>("endpoint", request.RequestUri?.AbsolutePath));
                 _preCompiledLogMessage(logger, request.RequestUri?.AbsolutePath, retryAttempt, timespan, null);
             });

    private static readonly Action<ILogger, string?, double, Exception?> _circuitBreakLogMessage =
        LoggerMessage.Define<string?, double>(
            logLevel: LogLevel.Warning,
            eventId: 103,
            formatString: "Circuit broken at endpoint {endpoint}! Break for {seconds}s.");

    private static Polly.CircuitBreaker.AsyncCircuitBreakerPolicy<HttpResponseMessage> CreateCircuitBreakerPolicy(
        HttpRequestMessage request, ILogger logger)
            => HttpPolicyExtensions
            .HandleTransientHttpError()
            .CircuitBreakerAsync(
                handledEventsAllowedBeforeBreaking: 5,
                durationOfBreak: TimeSpan.FromSeconds(5),
                onBreak: (result, breakDelay) =>
                {
                    Meters.UserServiceCircuitBreakerOpenCount.Add(1, new KeyValuePair<string, object?>("endpoint", request.RequestUri?.AbsolutePath));
                    _circuitBreakLogMessage(logger, request.RequestUri?.AbsolutePath, breakDelay.TotalSeconds, null);
                },
                onReset: () => logger.LogInformation("Circuit reset."),
                onHalfOpen: () => logger.LogInformation("Circuit is half-open; next call is trial.")
            );
}