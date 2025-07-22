using MediatR;

namespace efcoreddd.Domain.SharedKernel
{
    public abstract class BaseDomainEvent : INotification
    {
        public DateTimeOffset Timestamp
        { get; protected set; } = DateTimeOffset.UtcNow;
    }
}