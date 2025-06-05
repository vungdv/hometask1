using OpenTelemetry;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace app.Telemetry;

public static class Config
{
    public static void ConfigureTelemetry(this WebApplicationBuilder builder)
    {
        var otel = builder.Services.AddOpenTelemetry();

        // Config Resource
        otel.ConfigureResource(resourceBuilder =>
        {
            resourceBuilder.AddService(Tracing.TraceNames.Basic, Tracing.ServiceVersion);
        });

        // Add Metrics for ASP.NET Core and our custom metrics and export via OTLP
        otel.WithMetrics(metrics =>
        {
            // Metrics provider from OpenTelemetry
            metrics.AddAspNetCoreInstrumentation();
            //Our custom metrics
            metrics.AddMeter(Meters.MeterName);
            // Metrics provides by ASP.NET Core in .NET 8
            metrics.AddMeter("Microsoft.AspNetCore.Hosting");
            metrics.AddMeter("Microsoft.AspNetCore.Server.Kestrel");
        });

        // Add Tracing for ASP.NET Core and our custom ActivitySource and export via OTLP
        otel.WithTracing(tracing =>
        {
            tracing.AddAspNetCoreInstrumentation(o =>
            {
                o.EnrichWithHttpRequest = (activity, httpRequest) =>
                {
                    activity.SetTag("test", "test-value" + new Random().Next(0, 1000));
                    activity.SetTag("User_Agent", httpRequest.Headers.UserAgent);
                };
            });
            tracing.AddHttpClientInstrumentation();
            tracing.AddSource(Tracing.TraceNames.Services);
        });

        // Setup logging to be exported via OpenTelemetry
        builder.Logging.AddOpenTelemetry(logging =>
        {
            logging.IncludeFormattedMessage = true;
            logging.IncludeScopes = true;
        });

        // Export OpenTelemetry data via OTLP, using env vars for the configuration
        var OtlpEndpoint = builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"];
        if (OtlpEndpoint != null)
        {
            otel.UseOtlpExporter();
        }
    }
}