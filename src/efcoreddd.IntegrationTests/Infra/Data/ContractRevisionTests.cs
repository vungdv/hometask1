using efcoreddd.Domain.Contract;
using efcoreddd.Domain.Contract.Enums;
using efcoreddd.Domain.Contract.ValueObjects;
using efcoreddd.Extensions;
using efcoreddd.Infra.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace efcoreddd.IntegrationTests.Infra.Data;

public class ContractRevisionTests : IClassFixture<ApplicationFactory>
{
    private readonly ApplicationFactory _factory;
    private ContractDbContext _dbContext;

    public ContractRevisionTests(ApplicationFactory factory)
    {
        _factory = factory;
        _dbContext = _factory.Services.GetRequiredService<ContractDbContext>();
    }

    [Fact]
    public async Task CreateNewRevision()
    {
        var contractId = await NewContractAndAddRevision();
        await _dbContext.SaveChangesAsync();

        _dbContext.ChangeTracker.Clear();

        var storedContract = await _dbContext.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contractId)
            .SingleAsync();

        Assert.Equal(2, storedContract.Versions.Count());
    }

    [Fact]
    public async Task AddAuthorToCurrentVersion()
    {
        var contractId = await NewContractAndAddRevision();
        await AddAuthorToContract(contractId);

        _dbContext.ChangeTracker.Clear();

        var contractFromDB = await _dbContext.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contractId)
            .SingleAsync();

        Assert.Equal(2, contractFromDB.CurrentVersion().Authors.Count());
    }

    private async Task AddAuthorToContract(Guid contractId)
    {
        var contractFromDB = await _dbContext.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contractId)
            .SingleAsync();

        contractFromDB.AddAuthor(Author.UnsignedAuthor("new author", "author", "email", "phone"));
        await _dbContext.SaveChangesAsync();
    }

    private async Task<Guid> NewContractAndAddRevision()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");

        // Act
        _dbContext.Contracts.Add(contract);
        await _dbContext.SaveChangesAsync();

        _dbContext.ChangeTracker.DetectChanges();
        _dbContext.ChangeTracker.Clear();

        var storedContract = await _dbContext.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contract.Id)
            .SingleAsync();

        storedContract.CreateRevisionUsingSameSpecs(ModReason.Other,
                                                        "abc",
                                                        "title",
                                                        storedContract.CurrentVersion().CloneAuthors().ToList(),
                                                        null);
        return contract.Id;
    }
}