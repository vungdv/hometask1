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

        public DbSet<ContractAggregate> Contracts { get; set; } = null!;

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            modelBuilder.Entity<ContractAggregate>().ToTable("Contracts");
            modelBuilder.Entity<ContractVersion>()
                .OwnsOne(v => v.Specs, nav => { nav.ToJson(); });
            modelBuilder.Entity<ContractVersion>()
                .OwnsMany(v => v.Authors, nav =>
                {
                    nav.ToJson();
                    nav.OwnsOne(a => a.Name, na => { na.ToJson(); });
                });

            modelBuilder.Entity<ContractVersion>().Property("_hasRevisedSpecSet");

            modelBuilder.Entity<ContractAggregate>()
                .Property(c => c.DateInitiated).HasField("_initiated");

            modelBuilder.Entity<ContractAggregate>()
            .Property(c => c.ContractNumber).HasField("_contractNumber");
        }
    }
}