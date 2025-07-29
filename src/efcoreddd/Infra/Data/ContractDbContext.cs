using efcoreddd.Domain.Contract;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

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
            modelBuilder.Entity<ContractAggregate>()
               .Property(c => c.DateInitiated).HasField("_initiated");

            modelBuilder.Entity<ContractAggregate>()
            .Property(c => c.ContractNumber).HasField("_contractNumber");

            modelBuilder.Entity<ContractVersion>()
                .OwnsOne(v => v.Specs, nav => { nav.ToJson(); });
            modelBuilder.Entity<ContractVersion>()
                .OwnsMany(v => v.Authors, nav =>
                {
                    nav.ToJson();
                    nav.OwnsOne(a => a.Name, na => { na.ToJson(); });
                });

            modelBuilder.Entity<ContractVersion>().Property("_hasRevisedSpecSet");
            // As in the domain model we generate the Ids manually,
            // EFCore will default treat it as existing one and not track as new. 
            // by setting ValueGeneratedNever, it's strange but it works.
            // This one is to solved the Integration Test - ContractRevisionTests.CreateNewRevision 
            modelBuilder.Entity<ContractVersion>().Property(v => v.Id).ValueGeneratedNever();
        }

        protected override void ConfigureConventions(ModelConfigurationBuilder configurationBuilder)
        {
            configurationBuilder.Properties<ContractId>().HaveConversion<ContractIdConverter>();
            configurationBuilder.Properties<ContractVersionId>().HaveConversion<ContractVersionIdConverter>();
        }

        private sealed class ContractIdConverter : ValueConverter<ContractId, Guid>
        {
            public ContractIdConverter() : base(v => v.Value, v => new ContractId(v))
            { }
        }
        private sealed class ContractVersionIdConverter : ValueConverter<ContractVersionId, Guid>
        {
            public ContractVersionIdConverter() : base(v => v.Value, v => new(v))
            { }
        }
    }
}