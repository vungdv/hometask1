using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using efcoreddd.Domain.Contract;
using efcoreddd.Domain.Contract.Enums;
using efcoreddd.Domain.Contract.ValueObjects;
using efcoreddd.Infra.Data;
using efcoreddd.IntegrationTests.Infra.Containers;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace efcoreddd.IntegrationTests.Infra.Data;

public class ContractDbContextTests : IClassFixture<ApplicationFactory>
{
    private readonly ApplicationFactory _factory;
    private ContractDbContext _dbContext;

    public ContractDbContextTests(ApplicationFactory factory)
    {
        _factory = factory;
        _dbContext = _factory.Services.GetRequiredService<ContractDbContext>();
        _dbContext.Database.EnsureCreated();
    }

    [Fact]
    public async Task NewContractStoreCorrectId()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");

        // Act
        _dbContext.Contracts.Add(contract);
        await _dbContext.SaveChangesAsync();

        // Assert
        _dbContext.ChangeTracker.Clear();
        var storedContract = await _dbContext.Contracts.FindAsync(contract.Id);
        Assert.NotNull(storedContract);
        Assert.Equal(contract.Id, storedContract.Id);
    }

    [Fact]
    public async Task NewContractHasVersionWithSpecDefaults()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");

        // Act
        _dbContext.Contracts.Add(contract);
        await _dbContext.SaveChangesAsync();

        // Assert
        _dbContext.ChangeTracker.Clear();
        var storedContract = _dbContext.Contracts
                                    .Include(c => c.Versions)
                                    .Where(c => c.Id == contract.Id);
        Assert.Equal(
            ContractVersion.GetDefaultSpecs(),
            storedContract.First().Versions.First().Specs
        );
    }
    [Fact]
    public async Task NewContractHasContractNumberWhenGetFromDb()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");
        var contractNumber = contract.ContractNumber;
        // Act
        _dbContext.Contracts.Add(contract);
        await _dbContext.SaveChangesAsync();

        // Assert
        _dbContext.ChangeTracker.Clear();
        var storedContract = _dbContext.Contracts
                                    .Include(c => c.Versions)
                                    .Where(c => c.Id == contract.Id);
        Assert.Equal(
            contractNumber,
            storedContract.First().ContractNumber
        );
    }

    [Fact]
    public async Task NewContractWith_hasRevisedSpecSetIsFalse()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");
        var contractNumber = contract.ContractNumber;
        // Act
        _dbContext.Contracts.Add(contract);
        await _dbContext.SaveChangesAsync();

        // Assert
        _dbContext.ChangeTracker.Clear();
        var value = _dbContext.Contracts
                                    .Where(c => c.Id == contract.Id)
                                    .Select(c => EF.Property<bool>(c.Versions.FirstOrDefault(), "_hasRevisedSpecSet"))
                                    .FirstOrDefault();
        Assert.False(value);
    }

    [Fact]
    public async Task NewContractStoreJsonCompare()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");

        // Act
        _dbContext.Contracts.Add(contract);
        await _dbContext.SaveChangesAsync();

        // Assert
        _dbContext.ChangeTracker.Clear();
        var storedContract = await _dbContext.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contract.Id)
            .ToListAsync();

        var expected = JsonSerializer.Serialize(contract);
        var loadFromDB = JsonSerializer.Serialize(storedContract.FirstOrDefault());
        Assert.Equal(expected, loadFromDB);
    }
}