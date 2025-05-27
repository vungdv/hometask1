using System.Diagnostics.Metrics;
using app.Telemetry;

namespace app.Endpoints;

public static class WeatherEndpoints
{
    public static void MapWeatherEndpoints(this WebApplication app)
    {

        var summaries = new[]
        {
            "Freezing", "Bracing", "Chilly", "Cool", "Mild", "Warm", "Balmy", "Hot", "Sweltering", "Scorching"
        };
        // Custom metrics for the application
        var greeterMeter = new Meter("OTel.Example", "1.0.0");
        var countGreetings = greeterMeter.CreateCounter<int>("greetings.count", description: "Counts the number of greetings");

        app.MapGet("/weatherforecast", () =>
        {
            using var activity = Tracing.ServiceActivitySource.StartActivity("GreeterActivity");

            var forecast = Enumerable.Range(1, 5).Select(index =>
                new WeatherForecast
                (
                    DateOnly.FromDateTime(DateTime.Now.AddDays(index)),
                    Random.Shared.Next(-20, 55),
                    summaries[Random.Shared.Next(summaries.Length)]
                ))
                .ToArray();
            // Increment the custom counter
            countGreetings.Add(1);
            return forecast;
        })
        .WithName("GetWeatherForecast")
        .WithOpenApi();
    }

    record WeatherForecast(DateOnly Date, int TemperatureC, string? Summary)
    {
        public int TemperatureF => 32 + (int)(TemperatureC / 0.5556);
    }
}