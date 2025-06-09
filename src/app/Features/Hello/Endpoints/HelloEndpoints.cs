using System.Diagnostics;
using System.Net.Http.Headers;
using app.Telemetry;
using Google.Protobuf;
using Hello;
using OpenTelemetry;

namespace app.Features.Weather.Endpoints;

public static class HelloEndpoints
{
    static readonly Action<ILogger, string, Exception?> _preCompiledLogMessage =
    LoggerMessage.Define<string>(
        logLevel: LogLevel.Information,
        eventId: 101,
        formatString: "Request name is {request_name}");

    public static void MapHelloEndpoints(this WebApplication app)
    {
        app.MapGet("/hello", async (IHttpClientFactory httpClientFactory, ILogger<Program> logger) =>
        {
            var reply = await HelloProtobufAsync(httpClientFactory, logger);
            Meters.HelloCount.Add(1);
            return new { reply.Message };
        })
        .WithName("Hello")
        .WithOpenApi();
    }

    static async Task<HelloReply> HelloProtobufAsync(IHttpClientFactory httpClientFactory, ILogger logger)
    {
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
        var request = new HelloRequest { Name = "Alice2" };
        _preCompiledLogMessage(logger, request.Name, null);
        var content = new ByteArrayContent(request.ToByteArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("application/x-protobuf");

        var response = await client.PostAsync("http://go_app:8080/hello", content);
        var respBytes = await response.Content.ReadAsByteArrayAsync();

        return HelloReply.Parser.ParseFrom(respBytes);
    }
}