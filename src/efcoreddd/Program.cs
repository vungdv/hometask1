using efcoreddd.Infra.Data;
using efcoreddd.Services;
using Microsoft.EntityFrameworkCore;
namespace efcoreddd;

public class Program
{
    private Program() { }
    private static async Task Main(string[] args)
    {
        var builder = WebApplication.CreateBuilder(args);
        builder.Services.AddDbContext<ContractDbContext>(options =>
            options.UseNpgsql(builder.Configuration.GetConnectionString("DefaultConnection")));
        builder.Services.AddTransient<ContractService>();

        var app = builder.Build();

        app.MapGet("/", () => "Hello World!");

        await app.RunAsync();
    }
}