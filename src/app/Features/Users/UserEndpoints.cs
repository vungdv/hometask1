using System.Diagnostics;
using System.Net.Http.Headers;
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
        app.MapGet("/users",
            async (IHttpClientFactory httpClientFactory,
            ILogger<Program> logger,
            CancellationToken cancellationToken) =>
        {
            //TODO: for an unstable downstream service, beside a circuit breaker and retry policy.
            // We could also implement a fallback policy to return a cached response or a default value.
            var userId = Guid.NewGuid().ToString("d");
            Baggage.SetBaggage(Labels.UserId, userId);
            Activity.Current?.SetTag(Labels.UserId, userId);
            // Activity.Current?.SetTag(Labels.Dependency, Services.GoApp);
            using var activity = Tracing.ServiceActivitySource
                                           .StartActivity(
                                              name: "UserActivity",
                                           kind: ActivityKind.Client,
                                               tags: [
                                                   new (Labels.Dependency, Services.GoApp),
                                                   new (Labels.UserId, userId)
                                               ]);
            try
            {
                var reply = await CallConnectProtoAsync(httpClientFactory, cancellationToken);
                return new { reply.Users };
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Error calling UserService.ListUsers {user_id}", userId);
                throw;
            }
        })
        .WithName("Users")
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
}