WITH RECURSIVE catalog (id, name) AS (
    SELECT
        id,
        name::text
    FROM categories
    WHERE parent_id IS NULL

    UNION ALL

    SELECT
        ct.id,
        cl.name || ' : ' || ct.name
    FROM catalog cl
    JOIN categories ct
        ON cl.id = ct.parent_id
)
SELECT *
FROM catalog;