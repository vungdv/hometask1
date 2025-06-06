using System.Net.Http.Headers;
using app.Telemetry;
using Google.Protobuf;
using Hello;

namespace app.Features.Weather.Endpoints;

public static class HelloEndpoints
{
    public static void MapHelloEndpoints(this WebApplication app)
    {
        app.MapGet("/hello", async () =>
        {
            using var activity = Tracing.ServiceActivitySource.StartActivity("HelloActivity");
            var reply = await HelloProtobufAsync();
            Meters.HelloCount.Add(1);
            return new { reply.Message };
        })
        .WithName("Hello")
        .WithOpenApi();
    }

    static async Task<HelloReply> HelloProtobufAsync()
    {
        var client = new HttpClient();
        var request = new HelloRequest { Name = "Alice" };

        var content = new ByteArrayContent(request.ToByteArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("application/x-protobuf");

        var response = await client.PostAsync("http://go_app:8080/hello", content);
        var respBytes = await response.Content.ReadAsByteArrayAsync();

        return HelloReply.Parser.ParseFrom(respBytes);
    }
}