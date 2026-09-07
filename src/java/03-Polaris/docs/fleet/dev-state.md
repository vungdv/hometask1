# Developer State

- **Active Domain:** Order / Tests
- **Active Work Order:** [WO-001] Order Context API Contract Hardening & Test Suite, [WO-002] External API Performance & Contract Test Suite (k6)
- **Seam Progress:**
  - [x] Flyway Migration (`V{N}__*.sql`) - Schema existing (V1-V3)
  - [x] Entity & Repository - Order, OrderItem, Customer, Product entities & repos
  - [x] Domain Service & Invariants - OrderService throws ResourceNotFoundException on missing orders & IllegalStateException on invalid cancellations
  - [x] REST Controller & RFC 7807 Exception Mapping - OrderController returning ResponseEntity<OrderResponse>, GlobalExceptionHandler handling 404, 400, 409
  - [x] Automated Tests (Green) - 22/22 unit & integration tests passing; k6 performance & contract script configured
- **Blockers / Next Step:** None. All tasks completed and verified green.


