using System.Diagnostics.Metrics;

namespace app.Telemetry;

public static class Meters
{
    public const string MeterName = "HomeTask.Meter";
    private static readonly Meter GreeterMeter = new(MeterName, Tracing.ServiceVersion);
    public static readonly Counter<int> WeatherRequestCount = GreeterMeter.CreateCounter<int>("weather.request.count", description: "Counts the number of greetings");
}