using efcoreddd.Domain.Contract.Enums;
using efcoreddd.Domain.Contract.Services;
using efcoreddd.Domain.Contract.ValueObjects;
using efcoreddd.Domain.SharedKernel;

namespace efcoreddd.Domain.Contract;

public class ContractAggregate : BaseEntity<Guid>
{
#pragma warning disable CS8618 // Non-nullable field must contain a non-null value when exiting constructor. Consider adding the 'required' modifier or declaring as nullable.
    private ContractAggregate()
#pragma warning restore CS8618 // Non-nullable field must contain a non-null value when exiting constructor. Consider adding the 'required' modifier or declaring as nullable.
    {
        // EF Core requires a parameterless constructor for materialization
    }
    public ContractAggregate(DateOnly initDate, List<Author> authors, string workingTitle)
    {
        _initiated = initDate;
        Id = Guid.NewGuid();
        var baseattribs =
            VersionAttributeFactory.Create(Id, workingTitle, authors,
                                           ModReason.NewContract, "New Contract");
        ContractVersion version = ContractVersion.CreateNew(baseattribs);
        _contractNumber = GenerateContractNumber(version);
        AddVersion(version);
    }

    private void AddVersion(ContractVersion version)
    {
        _versions.Add(version);
        CurrentVersionId = version.Id;
    }

    public string ContractNumber => _contractNumber;
    public DateOnly DateInitiated => _initiated;
    public Guid CurrentVersionId { get; private set; }
    public Guid FinalVersionId { get; private set; }
    public bool Completed { get; private set; }
    public DateOnly CompletedDate { get; private set; } = DateOnly.MinValue;
    public DateOnly Fulfilled { get; private set; } = DateOnly.MinValue;
    public IEnumerable<ContractVersion> Versions => _versions;

    private string _contractNumber;
    private DateOnly _initiated;
    private readonly List<ContractVersion> _versions = [];

    public void CreateRevisionUsingSameSpecs
        (ModReason modReason, string modDescription, string title, List<Author> authors,
         DateOnly? customDeadline)
    {
        CreateRevision(modReason, modDescription, title, authors, customDeadline,
                       CurrentVersion().Specs, true);
    }

    public void CreateRevisionUsingNewSpecs
        (ModReason modReason, string modDescription, string title, List<Author> authors,
         DateOnly? customDeadline, SpecificationSet specs)
    {
        CreateRevision(modReason, modDescription, title, authors, customDeadline, specs, false);
    }

    private void CreateRevision
        (ModReason modReason, string modDescription, string title, List<Author> authors,
         DateOnly? customDeadline, SpecificationSet specs, bool sameSpecs)
    {
        var baseattribs =
            VersionAttributeFactory.Create(Id, title, authors, modReason, modDescription);
        ContractVersion revision;
        if (customDeadline == null)
        {
            revision = ContractVersion.CreateRevision(baseattribs, specs, !sameSpecs);
        }
        else
        {
            revision = ContractVersion.CreateRevisionWithCustomDeadline(
                baseattribs, specs, !sameSpecs, (DateOnly)customDeadline);
        }

        AddVersion(revision);
    }

    public IEnumerable<ContractVersion> GetVersion(Guid versionId)
    {
        return Versions.Where(v => v.Id == versionId);
    }

    public ContractVersion CurrentVersion()
    {
        return Versions.Single(v => v.Id == CurrentVersionId);
    }

    public void FinalVersionSignedByAllParties()
    {
        Completed = true;
        CompletedDate = DateOnly.FromDateTime(DateTime.UtcNow);
        FinalVersionId = CurrentVersionId;
    }

    public void CurrentVersionAcceptedVerbally()
    {
        CurrentVersion().VersionAccepted();
    }

    public void AddAuthor(Author author, ContractVersion version)
    {
        version.AddAuthor(author);
    }

    private string GenerateContractNumber(ContractVersion version)
    {
        var date = DateInitiated.ToShortDateString();
        var authorInits =
            new string(version.Authors.SelectMany(a => a.Name.ComplexInitials).ToArray());
        return $"{date}_{authorInits}";
    }
}