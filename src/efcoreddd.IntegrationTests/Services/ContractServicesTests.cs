using System.Text.Json;
using efcoreddd.Domain.Contract;
using efcoreddd.Domain.Contract.ValueObjects;
using efcoreddd.Infra.Data;
using efcoreddd.IntegrationTests.Infra;
using efcoreddd.Services;
using FluentAssertions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace efcoreddd.IntegrationTests.Services;

public class ContractServicesTests : IClassFixture<ApplicationFactory>
{
    private readonly ApplicationFactory _factory;
    private readonly ContractDbContext _context;
    private readonly ContractService _contractServices;

    public ContractServicesTests(ApplicationFactory factory)
    {
        _factory = factory;
        _context = _factory.Services.GetRequiredService<ContractDbContext>();
        _contractServices = _factory.Services.GetRequiredService<ContractService>();
    }

    [Fact]
    public async Task AddContract()
    {
        // Arrange
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");

        await _contractServices.AddAsync(contract);

        _context.ChangeTracker.Clear();
        var contractFromDB = await _context.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contract.Id)
            .SingleAsync();

        JsonSerializer.Serialize(contract).Should()
            .Be(JsonSerializer.Serialize(contractFromDB));
    }

    [Fact]
    public async Task AcceptCurrentVersionAsync()
    {
        // Arrange
        ContractAggregate contract = await StoreNewContractToDb();

        // Act
        await _contractServices.AcceptCurrentVersionAsync(contract.Id);
        _context.ChangeTracker.Clear();

        var contractFromDB = await _context.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contract.Id)
            .SingleAsync();

        Assert.True(contractFromDB.CurrentVersion().Accepted);
    }

    [Fact]
    public async Task FinalizeContractAsync()
    {
        // Arrange
        ContractAggregate contract = await StoreNewContractToDb();
        var completed = DateTime.Now;

        // Act
        await _contractServices.FinalizeContractAsync(contract.Id, completed);
        _context.ChangeTracker.Clear();

        var contractFromDB = await _context.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contract.Id)
            .SingleAsync();

        Assert.Equal(
            new { FinalVersionId = contract.CurrentVersionId, Completed = true, CompletedDate = DateOnly.FromDateTime(completed) },
            new { contractFromDB.FinalVersionId, contractFromDB.Completed, contractFromDB.CompletedDate }
        );
    }

    [Fact]
    public async Task FulfilContract()
    {
        // Arrange
        ContractAggregate contract = await StoreNewContractToDb();
        var fulfilled = DateTime.Now;

        // Act
        await _contractServices.FulfilContract(contract.Id, fulfilled);
        _context.ChangeTracker.Clear();

        var contractFromDB = await _context.Contracts
            .Include(c => c.Versions)
            .Where(c => c.Id == contract.Id)
            .SingleAsync();

        Assert.Equal(DateOnly.FromDateTime(fulfilled), contractFromDB.Fulfilled);
    }

    private async Task<ContractAggregate> StoreNewContractToDb()
    {
        var unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.UtcNow), unsignedAuthors, "booktitle");
        _context.Contracts.Add(contract);
        await _context.SaveChangesAsync();
        _context.ChangeTracker.Clear();
        return contract;
    }
}