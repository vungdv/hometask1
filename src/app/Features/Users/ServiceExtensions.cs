using app.Constants;
using app.PolyPolicies;
using Microsoft.Extensions.Options;

namespace Microsoft.Extensions.DependencyInjection;

public static class ServiceCollectionExtensions
{
    public class GoAppServiceClientOptions
    {
        public const string SectionName = "GO_APP";
        public string Endpoint { get; set; } = string.Empty;
        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(8);
    }

    public static IServiceCollection AddUserFeature(this IServiceCollection services)
    {
        services.Configure<GoAppServiceClientOptions>(options =>
        {
            var configuration = services.BuildServiceProvider().GetRequiredService<IConfiguration>();
            configuration.GetSection(GoAppServiceClientOptions.SectionName).Bind(options);
        });

        services.AddHttpClient(Services.GoApp, (sp, cfg) =>
        {
            var options = sp.GetRequiredService<IOptions<GoAppServiceClientOptions>>().Value;
            ArgumentNullException.ThrowIfNull(options.Endpoint);
            cfg.BaseAddress = new Uri(options.Endpoint);
            cfg.Timeout = options.Timeout;
        })
        .AddPolicyHandler(AppPoliciesExtensions.SelectPolicy);

        return services;
    }
}