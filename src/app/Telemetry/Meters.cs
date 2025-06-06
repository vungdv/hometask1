using System.Diagnostics.Metrics;

namespace app.Telemetry;

public static class Meters
{
    public const string MeterName = "HomeTask.Meter";
    private static readonly Meter HomeTaskMeter = new(MeterName, Tracing.ServiceVersion);
    public static readonly Counter<int> WeatherRequestCount = HomeTaskMeter.CreateCounter<int>("weather.request.count", description: "Counts the number of greetings");
    public static readonly Counter<int> HelloCount = HomeTaskMeter.CreateCounter<int>("hello.count", description: "Counts the number of hello");
}