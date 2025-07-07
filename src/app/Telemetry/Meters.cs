using System.Diagnostics.Metrics;
using app.Constants;

namespace app.Telemetry;

public static class Meters
{
    public const string MeterName = "HomeTask.Meter";
    private static readonly Meter HomeTaskMeter = new(MeterName, Tracing.ServiceVersion);
    public static readonly Counter<int> WeatherRequestCount =
        HomeTaskMeter.CreateCounter<int>(
            name: "weather.request.count",
            description: "Counts the number of greetings");
    public static readonly Counter<int> HelloCount =
        HomeTaskMeter.CreateCounter<int>(
            name: "hello.count",
            description: "Counts the number of hello");
    public static readonly Counter<long> UserServiceRetryCounter =
        HomeTaskMeter.CreateCounter<long>(
            name: "http_retry_attempts",
            unit: "attempts",
            description: "Counts HTTP retry attempts",
            tags:
            [
                new(Labels.Dependency, Services.GoApp)
            ]);
    public static readonly Counter<long> UserServiceCircuitBreakerOpenCount =
        HomeTaskMeter.CreateCounter<long>(
            name: "UserService.CircuitBreaker.OpenCount",
            unit: "events",
            description: "Counts circuit breaker open events",
            tags:
            [
                new(Labels.Dependency, Services.GoApp)
            ]
            );
}