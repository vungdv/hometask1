namespace efcoreddd.Services;

public class ContractSearchService
{
    //TODO: instead of build complex query based on ContractDbContext
    // We comeback to our problem and analyst what are available solutions? 
    // - 1. Build query based on ContractDbContext => this might lead to a thousand lines of code
    // as I observed during my career path. 
    // - 2. Build the search based purely on SQL, that would solved most of the problems efficiently. 
    // - 3. Use other tool (not a relational database) such as Open search
    // - 4. Extend with semantic search, vector search, ...
    public static IEnumerable<string> SearchContractForAnAuthorName(string author)
    {
        yield return "Sample";
    }
}