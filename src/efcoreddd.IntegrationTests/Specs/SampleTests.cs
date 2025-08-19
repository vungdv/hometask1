using efcoreddd.Domain.Contract;
using efcoreddd.Domain.Contract.Specifications;
using efcoreddd.Domain.Contract.ValueObjects;
using efcoreddd.Infra.Data;
using efcoreddd.IntegrationTests.Infra;
using efcoreddd.Services;
using Microsoft.Extensions.DependencyInjection;

namespace efcoreddd.IntegrationTests;

public class SampleTests : IClassFixture<ApplicationFactory>
{
    private ApplicationFactory _factory;
    private readonly ContractDbContext _context;
    private readonly ContractService _contractServices;
    public SampleTests(ApplicationFactory factory)
    {
        _factory = factory;
        _context = _factory.Services.GetRequiredService<ContractDbContext>();

        _contractServices = _factory.Services.GetRequiredService<ContractService>();
    }

    [Fact]
    public async Task Test()
    {
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");

        await _contractServices.AddAsync(contract);

        _context.ChangeTracker.Clear();

        var specs = new ContractByIdSpec(contract.Id);

        var contractFromDb = await _contractServices.GetContractsBySpecs(specs);

        Assert.Single(contractFromDb);
    }
}