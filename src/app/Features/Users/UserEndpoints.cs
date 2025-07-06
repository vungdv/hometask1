using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using app.Constants;
using app.Telemetry;
using Google.Protobuf;
using OpenTelemetry;
using User.V1;

namespace app.Features.Weather.Endpoints;

public static class UserEndpoints
{
    public static void MapUsersEndpoints(this WebApplication app)
    {
        app.MapGet("/users", async (IHttpClientFactory httpClientFactory, CancellationToken cancellationToken) =>
        {
            Baggage.SetBaggage("user_id", "123456");
            using var activity = Tracing.ServiceActivitySource.StartActivity("UserActivity", ActivityKind.Client);

            var reply = await CallConnectProtoAsync(httpClientFactory, cancellationToken);
            Meters.HelloCount.Add(1);
            return new { reply.Users };
        })
        .WithName("Users")
        .WithOpenApi();

        app.MapGet("json/users", async (IHttpClientFactory httpClientFactory, IConfiguration config) =>
        {
            //TODO: for an unstable downstream service, beside a circuit breaker and retry policy.
            // We could also implement a fallback policy to return a cached response or a default value.
            Baggage.SetBaggage("user_id", "123456");
            using var activity = Tracing.ServiceActivitySource.StartActivity("UserActivity", ActivityKind.Client);
            var reply = await CallConnectJsonAsync(httpClientFactory);
            Meters.HelloCount.Add(1);
            return new { reply.Users };
        })
        .WithName("jsonUsers")
        .WithOpenApi();

        app.MapGet("webgrpc/users", async (IHttpClientFactory httpClientFactory, IConfiguration config) =>
        {
            Baggage.SetBaggage("user_id", "123456");
            using var activity = Tracing.ServiceActivitySource.StartActivity("UserActivity", ActivityKind.Client);

            var reply = await CallGrpcWebAsync(httpClientFactory);
            Meters.HelloCount.Add(1);
            return new { reply.Users };
        })
        .WithName("webgrpcUsers")
        .WithOpenApi();
    }
    static async Task<ListUsersResponse> CallConnectProtoAsync(
        IHttpClientFactory httpClientFactory,
        CancellationToken cancellationToken)
    {
        //Create named client with support of polly for resilient. 
        var client = httpClientFactory.CreateClient(Services.GoApp);

        var request = new ListUsersRequest(); // Empty request
        var requestBytes = request.ToByteArray();
        // Envelop the request in protobuf.
        var content = new ByteArrayContent(requestBytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/proto"); // ✅ Required by Connect-go!
        content.Headers.Add("Connect-Protocol-Version", "1"); // ✅ Required by Connect-go!
        // make request via http
        var httpResponse = await client.PostAsync("/user.v1.UserService/ListUsers", content, cancellationToken);
        httpResponse.EnsureSuccessStatusCode();

        var responseBytes = await httpResponse.Content.ReadAsByteArrayAsync(cancellationToken);
        // parse the response from protobuf byte[] 
        return ListUsersResponse.Parser.ParseFrom(responseBytes);
    }

    static async Task<ListUsersResponse> CallConnectJsonAsync(IHttpClientFactory httpClientFactory)
    {
        //Create named client with support of polly for resilient. 
        var client = httpClientFactory.CreateClient(Services.GoApp);

        var request = new ListUsersRequest();
        var json = JsonSerializer.Serialize(request);
        var content = new StringContent(json, Encoding.UTF8, "application/json");

        var response = await client.PostAsync("/user.v1.UserService/ListUsers", content);
        response.EnsureSuccessStatusCode();

        var responseBody = await response.Content.ReadAsStringAsync();
        return JsonSerializer.Deserialize<ListUsersResponse>(responseBody) ?? new ListUsersResponse();
    }
    static async Task<ListUsersResponse> CallGrpcWebAsync(IHttpClientFactory httpClientFactory)
    {
        //Create named client with support of polly for resilient. 
        var client = httpClientFactory.CreateClient(Services.GoApp);

        var request = new ListUsersRequest();
        var content = new ByteArrayContent(request.ToByteArray());
        content.Headers.ContentType = new MediaTypeHeaderValue("application/grpc-web+proto");
        content.Headers.Add("x-user-agent", "grpc-web-csharp/1.0");

        var response = await client.PostAsync("/user.v1.UserService/ListUsers", content);
        response.EnsureSuccessStatusCode();

        var responseStream = await response.Content.ReadAsStreamAsync();

        // ⚠️ gRPC-Web returns a frame-prefixed stream; parsing may be more complex.
        using var ms = new MemoryStream();
        await responseStream.CopyToAsync(ms);
        var raw = ms.ToArray();

        // Skip gRPC-Web response envelope header (first 5 bytes)
        var payload = raw.Skip(5).ToArray();
        return ListUsersResponse.Parser.ParseFrom(payload);
    }

}