CREATE DATABASE identity_db;
CREATE DATABASE app_db;
-- Optional: Grant access to the default user
\connect identity_db
CREATE SCHEMA IF NOT EXISTS auth;

\connect app_db
CREATE SCHEMA IF NOT EXISTS go_app_db;
