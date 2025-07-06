using System.Diagnostics.Metrics;

namespace app.Telemetry;

public static class Meters
{
    public const string MeterName = "HomeTask.Meter";
    private static readonly Meter HomeTaskMeter = new(MeterName, Tracing.ServiceVersion);
    public static readonly Counter<int> WeatherRequestCount = HomeTaskMeter.CreateCounter<int>("weather.request.count", description: "Counts the number of greetings");
    public static readonly Counter<int> HelloCount = HomeTaskMeter.CreateCounter<int>("hello.count", description: "Counts the number of hello");
    public static readonly Counter<long> UserServiceRetryCounter = HomeTaskMeter.CreateCounter<long>("http_retry_attempts", description: "Counts HTTP retry attempts");
    public static readonly Counter<long> UserServiceCircuitBreakerOpenCount = HomeTaskMeter.CreateCounter<long>("UserService.CircuitBreaker.OpenCount", description: "Counts circuit breaker open events");
}