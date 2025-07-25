using System.Diagnostics.Contracts;
using efcoreddd.Domain.Contract;
using Microsoft.EntityFrameworkCore;

namespace efcoreddd.Infra.Data
{

    public class ContractDbContext : DbContext
    {
        public ContractDbContext(DbContextOptions<ContractDbContext> options)
            : base(options)
        {
        }

        public DbSet<ContractAggregate> Contracts => Set<ContractAggregate>(name: "Contracts");

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            modelBuilder.Entity<ContractVersion>().ComplexProperty(v => v.Specs);
            modelBuilder.Entity<ContractVersion>().OwnsMany(v => v.Authors).OwnsOne(a => a.Name);
            modelBuilder.Entity<ContractVersion>().Property("_hasRevisedSpecSet");

            modelBuilder.Entity<ContractAggregate>().Property(c => c.DateInitiated).HasField("_initiated");
            modelBuilder.Entity<ContractAggregate>().Property(c => c.ContractNumber).HasField("_contractNumber");
        }
    }
}