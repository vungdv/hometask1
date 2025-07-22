namespace efcoreddd.Domain.Contract.ValueObjects;

public readonly record struct SpecificationSet
(
 int AdvanceAmountUSD,
 int HardCoverRoyaltyPct,
 int SoftCoverRoyaltyPct,
 int DigitalRoyaltyPct,
 int TranslationRoyaltyUSD, bool PublicityProvided,
 bool AuthorAvailableForPR, int PromoCopiesForAuthor,
 decimal PriceForAddlAuthorCopiesUSD
);