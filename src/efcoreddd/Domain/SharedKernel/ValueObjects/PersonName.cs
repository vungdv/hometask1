namespace efcoreddd.Domain.SharedKernel.ValueObjects;

public class PersonName
{
    public string FirstName { get; init; }
    public string LastName { get; init; }

    public PersonName(string firstName, string lastName)
    {
        FirstName = firstName;
        LastName = lastName;
    }
    public string FullName => $"{FirstName.Trim()} {LastName.Trim()}";
    public string ReverseName => $"{LastName.Trim()} {FirstName.Trim()}";
    public string SingleInitials =>
        $"{FirstName.FirstOrDefault()}{LastName.FirstOrDefault()}";
    public string ComplexInitials =>
        $"{string.Concat(FirstName, "___")[..4]}" +
        $"{string.Concat(LastName, "___")[..4]}";

    public override bool Equals(object? obj)
    {
        return obj is PersonName name &&
               FirstName.Equals(name.FirstName) &&
               LastName.Equals(name.LastName);
    }

    public override int GetHashCode()
    {
        return HashCode.Combine(FirstName, LastName);
    }
}