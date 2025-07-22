namespace efcoreddd.Domain.SharedKernel;

public abstract class BaseEntity<TId>
{
    public TId Id { get; protected set; } = default!;
    public IList<BaseDomainEvent> Events { get; } = new List<BaseDomainEvent>();

}