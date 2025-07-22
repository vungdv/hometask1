# Overview

**The code in this project is an adjusted implementation during my learning the course EF Core and Domain-Driven Design
by Julie Lerman on pluralsight. see it here https://app.pluralsight.com/library/courses/ef-core-6-domain-driven-design/table-of-contents**

This project intent to demonstrate about the interconnection between DDD & EFCore:

- Data model
- Database schema migration
- Logging SQL and EF Core actions
- Working with related data
- Working with raw SQL, view, stored, and other database objects
- EFCore test

## DDD & Strategic Design

### Subdomain Taxonomy

- Core: makes biz stand out among competition. Confidential and under our control
- Supporting: Not necessarily unique, but we want to control it and keep it close to our chest.
- Generic: No secrets here. Same process as competitors. Use 3rd party service.

Example:

- Book Prep -> supporting
- **Talent & Book Acquisition -> Core** belows are three bounded contexts:
  - Book & Author Maintenance
  - Talent & Book Discovery
  - Contracting
- Warehouse & Shipping - Generic
- Production -> Generic
- Publicity (CRM) supporting
- Sales & Accounting -> Generic

### Bounded Context Relationships

- Co-operation relationships
  - Partnership: maintain communication
  - Shared Kernel: Share responsibility, use for infrastructure
- Customer/Supplier Relationships (Upstream/Downstream)
  - Conformist
  - Anti-Corruption Layer
  - Open-Host Service
