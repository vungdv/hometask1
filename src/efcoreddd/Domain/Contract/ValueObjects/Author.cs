using efcoreddd.Domain.SharedKernel.ValueObjects;

namespace efcoreddd.Domain.Contract.ValueObjects;

public class Author : ICloneable
{
#pragma warning disable CS8618 // Non-nullable field must contain a non-null value when exiting constructor. Consider adding the 'required' modifier or declaring as nullable.
    private Author()
#pragma warning restore CS8618 // Non-nullable field must contain a non-null value when exiting constructor. Consider adding the 'required' modifier or declaring as nullable.
    {
        // EF Core requires a parameterless constructor for materialization
    }
    public static Author UnsignedAuthor(string first,
                                        string last,
                                        string email,
                                        string phone)
    {
        return new Author(first, last, email, phone, false, Guid.Empty);
    }

    public static Author SignedAuthor(string first,
                                        string last,
                                        string email,
                                        string phone,
                                        Guid signedAuthorId)
    {
        return new Author(first, last, email, phone, true, signedAuthorId);
    }
    public Author(string first,
                  string last,
                  string email,
                  string phone,
                  bool signed,
                  Guid signedAuthorId)
    {
        Name = new PersonName(first, last);
        Email = email;
        Phone = phone;
        Signed = signed;
        SignedAuthorId = signedAuthorId;
    }

    public PersonName Name { get; init; }
    public string Email { get; init; }
    public string Phone { get; init; }
    public bool Signed { get; init; }
    public Guid SignedAuthorId { get; init; }
    public string FullName => Name.FullName;
    public Author FixName(string first, string last)
    {
        return new Author(first, last, Email, Phone, Signed, SignedAuthorId);
    }
    public Author AddPhone(string newPhone)
    {
        return new Author(Name.FirstName, Name.LastName, Email, newPhone, Signed, SignedAuthorId);
    }
    public override bool Equals(object? obj)
    {
        return obj is Author author &&
               Name.Equals(author.Name) &&
               Email == author.Email &&
               Phone == author.Phone &&
               Signed == author.Signed &&
               SignedAuthorId.Equals(author.SignedAuthorId);
    }

    public override int GetHashCode()
    {
        return HashCode.Combine(Name, Email, Phone, Signed, SignedAuthorId);
    }

    public object Clone()
    {
        return new Author(Name.FirstName, Name.LastName, Email, Phone, Signed, SignedAuthorId);
    }
}