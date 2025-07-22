using System.Text.Json;
using efcoreddd.Domain.Contract.Enums;
using efcoreddd.Domain.Contract.ValueObjects;
using efcoreddd.Domain.SharedKernel;
using static efcoreddd.Domain.Contract.Services.VersionAttributeFactory;

namespace efcoreddd.Domain.Contract;

public class ContractVersion : BaseEntity<Guid>
{
    public static ContractVersion CreateNew(BaseAttributes attribs)
    {
        return new ContractVersion(GetDefaultSpecs(), attribs, null, false);
    }

    public static ContractVersion CreateRevision
        (BaseAttributes attribs, SpecificationSet specs, bool hasRevisedSpecs)
    {
        return new ContractVersion(specs, attribs, null, hasRevisedSpecs); ;
    }

    public static ContractVersion CreateRevisionWithCustomDeadline
        (BaseAttributes attribs, SpecificationSet specs, bool hasRevisedSpecs, DateOnly deadline)
    {
        return new ContractVersion(specs, attribs, deadline, hasRevisedSpecs);
    }
    private ContractVersion(SpecificationSet specs, BaseAttributes attribs,
                              DateOnly? deadline, bool revisedSpecs)
    {
        Id = Guid.NewGuid();
        Specs = specs;
        _hasRevisedSpecSet = revisedSpecs;
        DateCreated = DateTime.Today;
        DateSentToAuthors = DateCreated.AddDays(1);
        if (deadline is null)
        { AcceptanceDeadline = DateOnly.FromDateTime(DateCreated.AddDays(10)); }
        else
        { AcceptanceDeadline = (DateOnly)deadline; }
        ModificationReason = attribs.ModReason;
        ModificationDetails = attribs.ModDescription;
        WorkingTitle = attribs.WorkingTitle;
        _authors.AddRange(attribs.Authors);

    }
    private bool _hasRevisedSpecSet;
    public SpecificationSet Specs { get; init; }

    public Guid ContractId { get; init; }
    public string WorkingTitle { get; init; }
    public DateTime DateCreated { get; init; }
    public DateTime DateSentToAuthors { get; private set; }
    public DateOnly AcceptanceDeadline { get; private set; }
    public string ModificationDetails { get; init; }
    public ModReason ModificationReason { get; init; }
    public bool Accepted { get; private set; }
    private readonly List<Author> _authors = [];
    public IEnumerable<Author> Authors => _authors.AsReadOnly();
    public void SentToAuthors(DateTime dateSent)
    {
        DateSentToAuthors = dateSent;
    }
    public void AddAuthor(Author author)
    {
        _authors.Add(author);
    }
    public void VersionAccepted()
    {
        Accepted = true;
    }
    public static SpecificationSet GetDefaultSpecs()
    {
        //read from json file
        string fileName = "DefaultSpecificationSet.json";
        string jsonString = File.ReadAllText(fileName);
        return JsonSerializer.Deserialize<SpecificationSet>(jsonString)!;
    }
}