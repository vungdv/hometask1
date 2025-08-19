using Ardalis.Specification;
using Ardalis.Specification.EntityFrameworkCore;
using efcoreddd.Domain.Contract;
using efcoreddd.Infra.Data;
using Microsoft.EntityFrameworkCore;

namespace efcoreddd.Services;

/// <summary>
/// Mainly focus on OLTP (online transaction processing), which supports to manipulate the core flow on a Contract such as: 
/// - Add new contract
/// - Add a revision
/// - Finalize, fulfill, ... contract
/// For the other, such as search for contracts, which should be solved separately. 
/// </summary>
public class ContractService
{
    private readonly ContractDbContext _contractDbContext;
    public ContractService(ContractDbContext context)
    {
        _contractDbContext = context;
    }
    public async Task AddAsync(ContractAggregate contract)
    {
        _contractDbContext.Contracts.Add(contract);
        await _contractDbContext.SaveChangesAsync();
    }

    public async Task AddRevisionAsync(ContractAggregate contract)
    {
        // track the contract as modified.
        _contractDbContext.Entry(contract).State = EntityState.Modified;

        // add the current version of the contract.
        _contractDbContext.Add(contract.CurrentVersion());
        await _contractDbContext.SaveChangesAsync();

        // Discussion: why not just treat the contract aggregate as a root entity and save it?
        // The rule defines in the aggregate root should guarantee old versions are readonly.
    }

    public async Task UpdateAsync(ContractAggregate contract)
    {
        _contractDbContext.Entry(contract).State = EntityState.Modified;
        if (contract.Versions.Any()
            && contract.CurrentVersion().Id == contract.CurrentVersionId)
        {
            _contractDbContext.Entry(contract.CurrentVersion()).State = EntityState.Modified;
        }
        await _contractDbContext.SaveChangesAsync();
    }
    /// <summary>
    /// By using Guid here, all the contract, contract version share the same Id type. 
    /// So it might be accidentally a developer pass ContractVersionId as a ContractId. 
    /// In language such as golang, we have alias type
    /// </summary>
    /// <param name="contractId"></param>
    /// <returns></returns>
    public async Task AcceptCurrentVersionAsync(ContractId contractId)
    {
        await _contractDbContext.Set<ContractVersion>()
            .Where(c => _contractDbContext.Contracts.Where(c => c.Id == contractId)
                        .Select(c => c.CurrentVersionId).Contains(c.Id))
            .ExecuteUpdateAsync(c =>
                c.SetProperty(p => p.Accepted, v => true));
    }

    public async Task FinalizeContractAsync(ContractId contractId, DateTime completed)
    {
        await _contractDbContext.Contracts
            .Where(c => c.Id == contractId)
            .ExecuteUpdateAsync(c =>
                c.SetProperty(p => p.FinalVersionId, v => v.CurrentVersionId)
                 .SetProperty(p => p.Completed, v => true)
                 .SetProperty(p => p.CompletedDate,
                              v => DateOnly.FromDateTime(completed.ToUniversalTime())));
    }

    public async Task FulfilContract(ContractId contractId, DateTime fulfilled)
    {
        await _contractDbContext.Contracts
            .Where(c => c.Id == contractId)
            .ExecuteUpdateAsync(c =>
                c.SetProperty(p => p.Fulfilled,
                              v => DateOnly.FromDateTime(fulfilled.ToUniversalTime())));
    }

    public async Task<IEnumerable<ContractAggregate>> GetContractsBySpecs(ISpecification<ContractAggregate> specs)
    {
        return await _contractDbContext.Contracts.WithSpecification(specs).ToListAsync();
    }
}