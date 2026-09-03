# Introduction
This is a java learning project for a .net developer who was/is switching to java springboot.

# Approach
I use top-down approach: 
- Draw from highlevel architecture for a production ready system
- Identity the GAPs top-down
- Choose one of the GAPs and deep dive 

## Architecture

```mermaid
flowchart TB
    subgraph L1["User Layer"]
        Browser["Client Browser"]
    end

    subgraph L2["Gateway Layer"]
        Nginx["Nginx<br/>(Reverse Proxy / TLS)"]
    end

    subgraph L3["Services Layer"]
        App["App<br/>(Polaris - Spring Boot)"]
        Keycloak["Keycloak<br/>(Identity Provider)"]
    end

    subgraph L4["Data Layer"]
        H2["H2<br/>(Polaris DB)"]
        Postgres["Postgres<br/>(Keycloak DB)"]
    end

    Browser -->|HTTPS 443| Nginx
    Nginx -->|proxy_pass| App
    Nginx -->|proxy_pass| Keycloak
    App -->|OAuth2/JWT validation| Keycloak
    App --> H2
    Keycloak --> Postgres
```

# GAPS

## Java starter
- init a java srping boot web api project
- package management, common packages for a springboot web project
- maven, compile, run, test
- testing for a springboot web project with WebMVC layer, Services layer, JPA (java persistence layer)
- mocking framework
- integration test

## Intermediate
- @Configuration, @Value
- SecurityFilter with auth2
- Spring-doc openapi

## Advanced
To setup production like system:
- Database migration with flyway
- Gateway (use nginx for local, setup self-signed certificate for domain polaris.local)
- Observability
- Optimistic & pessimistic approaches for concurency problem.

## Learn java idioms
- Read effective java