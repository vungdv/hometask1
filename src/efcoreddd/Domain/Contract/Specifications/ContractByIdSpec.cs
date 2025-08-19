using Ardalis.Specification;

namespace efcoreddd.Domain.Contract.Specifications;

public class ContractByIdSpec : Specification<ContractAggregate>
{
    public ContractByIdSpec(ContractId contractId)
    {
        Query.Where(x => x.Id == contractId);
    }
}