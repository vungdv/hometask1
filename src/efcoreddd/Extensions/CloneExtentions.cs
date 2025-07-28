namespace efcoreddd.Extensions;

public static class CloneExtensions
{
    public static IList<T> Clone<T>(this IList<T> source) where T : ICloneable
    {
        ArgumentNullException.ThrowIfNull(source);

        var clonedList = new List<T>(source.Count);
        foreach (var item in source)
        {
            clonedList.Add((T)item.Clone());
        }
        return clonedList;
    }

    public static IEnumerable<T> Clone<T>(this IEnumerable<T> source) where T : ICloneable
    {
        ArgumentNullException.ThrowIfNull(source);

        foreach (var item in source)
        {
            yield return (T)item.Clone();
        }
    }
}