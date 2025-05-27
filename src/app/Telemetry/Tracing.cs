using System.Diagnostics;

namespace app.Telemetry;

public static class Tracing
{
    public static readonly Version AssemblyVersion = typeof(Tracing).Assembly.GetName().Version!;
    public static string ServiceVersion => $"{AssemblyVersion.Major}.{AssemblyVersion.Minor}.{AssemblyVersion.Build}";
    public static class TraceNames
    {
        public const string Basic = "weather-app";
        public readonly static string Services = Basic + ".Services";
    }
    // It’s generally recommended to define ActivitySource once per app/service that is been instrumented, 
    // but you can instantiate several ActivitySources if that suits your scenario. 
    // https://opentelemetry.io/docs/languages/dotnet/instrumentation/#setting-up-an-activitysource
    public static ActivitySource ServiceActivitySource { get; } = new ActivitySource(TraceNames.Services, ServiceVersion);
}