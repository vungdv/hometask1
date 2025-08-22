using Microsoft.Extensions.Configuration;
using Testcontainers.PostgreSql;

namespace efcoreddd.IntegrationTests.Infra.Containers;

public sealed class PostgreConfigurationSource : IConfigurationSource
{
    private readonly string _containerName;
    public PostgreConfigurationSource(string containerName)
    {
        _containerName = containerName ?? throw new ArgumentNullException(nameof(containerName));
    }
    public IConfigurationProvider Build(IConfigurationBuilder builder)
    {
        return new PostgreConfigurationProvider(_containerName);
    }
}

public sealed class PostgreConfigurationProvider : ConfigurationProvider
{
    private readonly string _containerName;
    public PostgreConfigurationProvider(string containerName)
    {
        _containerName = containerName ?? throw new ArgumentNullException(nameof(containerName));
    }
    private static readonly TaskFactory TaskFactory = new TaskFactory(CancellationToken.None, TaskCreationOptions.None, TaskContinuationOptions.None, TaskScheduler.Default);

    public override void Load()
    {
        // Until the asynchronous configuration provider is available,
        // we use the TaskFactory to spin up a new task that handles the work:
        // https://github.com/dotnet/runtime/issues/79193
        // https://github.com/dotnet/runtime/issues/36018
        TaskFactory.StartNew(LoadAsync)
          .Unwrap()
          .ConfigureAwait(false)
          .GetAwaiter()
          .GetResult();
    }
    public async Task LoadAsync()
    {
        Console.WriteLine("Loading PostgreSQL configuration...");
        var postgreContainer = new PostgreSqlBuilder()
            .WithDatabase("efcoreddd")
            .WithUsername("postgres")
            .WithPassword("password")
            .WithReuse(true)
            .WithLabel(_containerName, "")
            .Build();

        if (postgreContainer.State != DotNet.Testcontainers.Containers.TestcontainersStates.Running)
        {
            await GlobalLocks.PostgreInitContainerLock.WaitAsync();
            try
            {
                await postgreContainer.StartAsync();
            }
            finally
            {

                GlobalLocks.PostgreInitContainerLock.Release();
            }
        }

        Data["ConnectionStrings:DefaultConnection"] = postgreContainer.GetConnectionString();
    }
}