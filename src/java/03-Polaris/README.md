# Introduction
This is a simple demonstration of new generation of application with LLM.
This is an internal AI user assistant for an organisation, where user can
- Ask about a product (TBD, data generation will implement later)
- Place an order
- Ask about the order status
- Cancel the order in particular conditions 


## Architecture

Given we have a backend like this with API ready, we will integrate with AI assistent via MCP in later.

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
