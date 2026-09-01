INSERT INTO customers (full_name, email, phone) VALUES
('Alice Tran', 'alice.tran@example.com', '0901111111'),
('Ben Nguyen', 'ben.nguyen@example.com', '0902222222'),
('Chi Le', 'chi.le@example.com', '0903333333');

INSERT INTO products (sku, name, price, stock_qty) VALUES
('NG-EARBUD-01', 'Nova Wireless Earbuds', 49.90, 120),
('NG-WATCH-01', 'Nova Smart Watch', 89.90, 60),
('NG-SPEAKER-01', 'Nova Bluetooth Speaker', 39.90, 80),
('NG-CHARGER-01', 'Nova 65W Fast Charger', 24.90, 200),
('NG-CASE-01', 'Nova Phone Case', 14.90, 300);

-- One order per lifecycle stage so the demo can exercise every rule
INSERT INTO orders (order_number, customer_id, status, total_amount, placed_at, updated_at) VALUES
('ORD-1001', 1, 'PLACED',     49.90,  DATEADD('HOUR', -1, CURRENT_TIMESTAMP), DATEADD('HOUR', -1, CURRENT_TIMESTAMP)),
('ORD-1002', 1, 'CONFIRMED',  89.90,  DATEADD('DAY', -1, CURRENT_TIMESTAMP),  DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
('ORD-1003', 2, 'PARCELED',   39.90,  DATEADD('DAY', -2, CURRENT_TIMESTAMP),  DATEADD('DAY', -2, CURRENT_TIMESTAMP)),
('ORD-1004', 2, 'DELIVERING', 24.90,  DATEADD('DAY', -3, CURRENT_TIMESTAMP),  DATEADD('DAY', -1, CURRENT_TIMESTAMP)),
('ORD-1005', 3, 'DELIVERED',  64.80,  DATEADD('DAY', -7, CURRENT_TIMESTAMP),  DATEADD('DAY', -5, CURRENT_TIMESTAMP)),
('ORD-1006', 3, 'CANCELLED',  14.90,  DATEADD('DAY', -4, CURRENT_TIMESTAMP),  DATEADD('DAY', -4, CURRENT_TIMESTAMP));

INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
(1, 1, 1, 49.90),
(2, 2, 1, 89.90),
(3, 3, 1, 39.90),
(4, 4, 1, 24.90),
(5, 3, 1, 39.90),
(5, 4, 1, 24.90),
(6, 5, 1, 14.90);