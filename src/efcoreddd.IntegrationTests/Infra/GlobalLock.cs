namespace efcoreddd.IntegrationTests.Infra;

internal static class GlobalLocks
{
    internal static readonly SemaphoreSlim PostgreInitContainerLock = new(1, 1);
}