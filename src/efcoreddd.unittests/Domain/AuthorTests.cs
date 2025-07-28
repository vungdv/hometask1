using efcoreddd.Domain.Contract.ValueObjects;

namespace efcoreddd.UnitTests.Domain;

public class AuthorTests
{
    [Fact]
    public void CanCreateSignedAuthor()
    {
        var signed = Guid.NewGuid();
        var author = Author.SignedAuthor("John", "Doe", "", "123-456-7890", signed);

        Assert.Equal(
            new { Signed = true, SignedAuthorId = signed },
            new { author.Signed, author.SignedAuthorId });
    }

    [Fact]
    public void CanCreateUnsignedAuthor()
    {
        var author = Author.UnsignedAuthor("Jane", "Doe", "", "098-765-4321");
        Assert.False(author.Signed);
    }
    [Fact]
    public void CanCreateANewAuthorViaFixAuthorName()
    {
        var author = Author.UnsignedAuthor("Jane", "Doe", "", "098-765-4321");
        var newAuthor = author.FixName("Jane", "Smith");

        Assert.Equal(
            new { FullName = "Jane Smith", Email = "", Phone = "098-765-4321" },
            new { newAuthor.FullName, newAuthor.Email, newAuthor.Phone }
        );
    }

    [Fact]
    public void CanCreateANewAuthorViaAddPhone()
    {
        var author = Author.UnsignedAuthor("Jane", "Doe", "", "098-765-4321");
        var newAuthor = author.AddPhone("123-456-7890");

        Assert.Equal(
            new { FullName = "Jane Doe", Email = "", Phone = "123-456-7890" },
            new { newAuthor.FullName, newAuthor.Email, newAuthor.Phone }
        );
    }

    [Fact]
    public void UnsignedAuthorHasNoSignedAuthorId()
    {
        var author = Author.UnsignedAuthor("Jane", "Doe", "", "098-765-4321");
        Assert.Equal(Guid.Empty, author.SignedAuthorId);
    }

    [Fact]
    public void CloneUnsignedAuthorShouldEqual()
    {
        var author = Author.UnsignedAuthor("Jane", "Doe", "", "098-765-4321");
        var clonedAuthor = (Author)author.Clone();
        Assert.Equal(author, clonedAuthor);
    }
}