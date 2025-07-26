using efcoreddd.IntegrationTests.Infra.Containers;
using Microsoft.AspNetCore.Hosting;
using efcoreddd;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using efcoreddd.Infra.Data;

namespace efcoreddd.IntegrationTests.Infra;

public class ApplicationFactory : WebApplicationFactory<Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Test");
        builder.ConfigureAppConfiguration(configure =>
        {
            configure.Add(new PostgreConfigurationSource("efcoreddd.IntegrationTests"));
        });

        builder.ConfigureServices(services =>
        {
            services.BuildServiceProvider().GetRequiredService<ContractDbContext>().Database.EnsureCreated();
        });
    }
}