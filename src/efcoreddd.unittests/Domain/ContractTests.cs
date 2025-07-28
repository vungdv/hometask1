using System.Reflection;
using efcoreddd.Domain.Contract;
using efcoreddd.Domain.Contract.Enums;
using efcoreddd.Domain.Contract.ValueObjects;

namespace efcoreddd.UnitTests.Domain;

public class ContractTests
{
    List<Author> _unsignedAuthors;
    ContractAggregate _contract;

    public ContractTests()
    {
        _unsignedAuthors = new List<Author> { Author.UnsignedAuthor("first", "last", "email", "phone") };
        _contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.Now), _unsignedAuthors, "booktitle");
    }
    [Fact]
    public void NewContractHasId()
    {
        Assert.NotEqual(Guid.Empty, _contract.Id);
    }
    [Fact]
    public void NewContractHasExpectedContractNumber()
    {
        Assert.Equal($"{DateTime.Today.ToShortDateString()}_firslast", _contract.ContractNumber);
    }

    [Fact]
    public void VersionRevisionResultsinChangeInCurrentVersionId()
    {
        var contract = new ContractAggregate(DateOnly.FromDateTime(DateTime.Now), _unsignedAuthors, "booktitle");
        var firstVersionId = contract.CurrentVersion().Id;
        contract.CreateRevisionUsingSameSpecs(ModReason.Other, "abc", "xyz", _unsignedAuthors, null);
        Assert.NotEqual(firstVersionId, contract.CurrentVersionId);
    }

    [Fact]
    public void VersionRevisionResultsinNonEmptyVersionId()
    {
        var firstVersionId = _contract.CurrentVersion().Id;
        _contract.CreateRevisionUsingSameSpecs(ModReason.Other, "abc", "xyz", _unsignedAuthors, null);
        Assert.NotEqual(Guid.Empty, _contract.CurrentVersion().Id);
    }
    [Fact]
    public void AddingContractRevisionIncreasestheNumberOfVersions()
    {
        _contract.CreateRevisionUsingSameSpecs
            (ModReason.ChangeAttributes, "abc", "title", _unsignedAuthors, null);
        Assert.Equal(2, _contract.Versions.Count());
    }
    [Fact]
    public void ContractRevisionResultsInCorrectCurrentVersion()
    {
        _contract.CreateRevisionUsingSameSpecs(ModReason.ChangeAttributes, "abc", "title", _unsignedAuthors, null);
        var ccv = _contract.CurrentVersion();
        Assert.Equal
           (new string[] { ModReason.ChangeAttributes.ToString(), "abc", "title", "fl" },
            new string[] { ccv.ModificationReason.ToString(), ccv.ModificationDetails,
                           ccv.WorkingTitle, ccv.Authors.FirstOrDefault().Name.SingleInitials });
    }

    [Fact]
    public void ContractRevisionWithSameSpecsSetsHasRevisedSpecsCorrectValue()
    {
        _contract.CreateRevisionUsingSameSpecs(ModReason.ChangeAttributes, "abc", "title",
                                               _unsignedAuthors, null);
        var ccv = _contract.CurrentVersion();
        var theField = typeof(ContractVersion)
            .GetField("_hasRevisedSpecSet",
                       BindingFlags.NonPublic | BindingFlags.Public | BindingFlags.Instance);
        var hasRevisedSpecs = (bool)theField.GetValue(ccv);

        Assert.False(hasRevisedSpecs);
    }

    [Fact]
    public void DerivedContractIdIsProtected()
    {
        var prop = typeof(ContractAggregate).GetProperty("Id");
        Assert.True(prop.SetMethod.IsFamily);
    }
}