using System.Diagnostics;
using System.Net.Http.Headers;
using app.Telemetry;
using Google.Protobuf;
using Hello;
using OpenTelemetry;

namespace app.Features.Weather.Endpoints;

public static class HelloEndpoints
{
    private const string GOAPPServiceEndpointConfig = "go_app:endpoint";
    static readonly Action<ILogger, string, Exception?> _preCompiledLogMessage =
    LoggerMessage.Define<string>(
        logLevel: LogLevel.Information,
        eventId: 101,
        formatString: "Request name is {request_name}");

    public static void MapHelloEndpoints(this WebApplication app)
    {
        app.MapGet("/hello", async (IHttpClientFactory httpClientFactory, IConfiguration config, ILogger<Program> logger) =>
        {
            var reply = await HelloProtobufAsync(httpClientFactory, config, logger);
            Meters.HelloCount.Add(1);
            return new { reply.Message };
        })
        .WithName("Hello")
        .WithOpenApi();
    }

    static async Task<HelloReply> HelloProtobufAsync(IHttpClientFactory httpClientFactory, IConfiguration config, ILogger logger)
    {
        var goappEndpoint = config.GetValue<string>(GOAPPServiceEndpointConfig);
        ArgumentNullException.ThrowIfNull(goappEndpoint);

        using var activity = Tracing.ServiceActivitySource.StartActivity("HelloActivity", ActivityKind.Client);

        if (Activity.Current != null)
        {
            Activity.Current?.SetBaggage("tenant_id", "123");
            Activity.Current?.SetTag("tenant_id", "123");
        }
        else
        {
            Baggage.SetBaggage("tenant_id", "321");
        }

        Baggage.SetBaggage("user_id", "123456");

        var client = httpClientFactory.CreateClient();

        client.BaseAddress = new Uri(uriString: goappEndpoint);

        var request = new HelloRequest { Name = "Alice2" };
        _preCompiledLogMessage(logger, request.Name, null);
        var content = new ByteArrayContent(request.ToByteArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("application/x-protobuf");

        var response = await client.PostAsync("/hello", content);
        var respBytes = await response.Content.ReadAsByteArrayAsync();

        return HelloReply.Parser.ParseFrom(respBytes);
    }
}