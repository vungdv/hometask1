using System.Diagnostics;
using System.Net.Http.Headers;
using app.Telemetry;
using Google.Protobuf;
using Hello;
using OpenTelemetry;

namespace app.Features.Weather.Endpoints;

public static class HelloEndpoints
{
    public static void MapHelloEndpoints(this WebApplication app)
    {
        app.MapGet("/hello", async (IHttpClientFactory httpClientFactory) =>
        {
            var reply = await HelloProtobufAsync(httpClientFactory);
            Meters.HelloCount.Add(1);
            return new { reply.Message };
        })
        .WithName("Hello")
        .WithOpenApi();
    }

    static async Task<HelloReply> HelloProtobufAsync(IHttpClientFactory httpClientFactory)
    {
        using var activity = Tracing.ServiceActivitySource.StartActivity("HelloActivity", ActivityKind.Client);

        if (Activity.Current != null)
        {
            Activity.Current?.SetBaggage("tenant-id", "123");
            Activity.Current?.SetTag("tenant-id", "123");
        }
        else
        {
            Baggage.SetBaggage("tenant-id", "321");
        }

        Baggage.SetBaggage("user-id", "123456");

        var client = httpClientFactory.CreateClient();
        var request = new HelloRequest { Name = "Alice" };

        var content = new ByteArrayContent(request.ToByteArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("application/x-protobuf");

        var response = await client.PostAsync("http://go_app:8080/hello", content);
        var respBytes = await response.Content.ReadAsByteArrayAsync();

        return HelloReply.Parser.ParseFrom(respBytes);
    }
}