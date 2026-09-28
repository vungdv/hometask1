-- V13__order_number_sequence.sql
-- Order numbers come from a database sequence (plan S4 / G10) instead of a per-JVM in-memory
-- counter seeded from the clock, which could hand out the same number after a restart or on a
-- second replica and then fail on the UNIQUE order_number constraint.
--
-- The service formats nextval as ORD-%06d. The sequence starts above the largest numeric suffix
-- already in use (seeded ORD-1001.., and any number the old counter produced), so a generated
-- number can never collide with an existing one.

CREATE SEQUENCE order_number_seq START WITH 1 INCREMENT BY 1;

SELECT setval(
    'order_number_seq',
    COALESCE((SELECT MAX(CAST(SUBSTRING(order_number FROM 5) AS BIGINT))
              FROM orders
              WHERE order_number ~ '^ORD-[0-9]{1,18}$'), 0) + 1,
    false);
