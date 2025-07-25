using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace efcoreddd.Infra.Data;

public class ContractDbContextDesignlFactory : IDesignTimeDbContextFactory<ContractDbContext>
{
    public ContractDbContext CreateDbContext(string[] args)
    {
        var optionsBuilder = new DbContextOptionsBuilder<ContractDbContext>();

        optionsBuilder.UseNpgsql("Host=localhost;Database=efcore_db;Username=admin;Password=secret");

        return new ContractDbContext(optionsBuilder.Options);
    }
}