-- Fictional Acme Corp employees, laptops and open tickets for the IT helpdesk demo.

INSERT INTO employee (username, full_name, email, department, location, manager) VALUES
  ('jdoe',      'John Doe',          'john.doe@acme.example',          'Engineering', 'Berlin',    'mgarcia'),
  ('asmith',    'Alice Smith',       'alice.smith@acme.example',       'Finance',     'London',    'rsingh'),
  ('pnair',     'Priya Nair',        'priya.nair@acme.example',        'Sales',       'Bangalore', 'rsingh'),
  ('mgarcia',   'Miguel Garcia',     'miguel.garcia@acme.example',     'Engineering', 'Madrid',    NULL),
  ('tchen',     'Tina Chen',         'tina.chen@acme.example',         'Marketing',   'Singapore', 'rsingh'),
  ('lkowalski', 'Lena Kowalski',     'lena.kowalski@acme.example',     'HR',          'Warsaw',    'rsingh'),
  ('oadeyemi',  'Olu Adeyemi',       'olu.adeyemi@acme.example',       'Support',     'Lagos',     'mgarcia'),
  ('rsingh',    'Ravi Singh',        'ravi.singh@acme.example',        'Operations',  'London',    NULL);

INSERT INTO asset (asset_tag, type, model, assigned_to, purchased_on, warranty_until, status) VALUES
  ('LAP-2041', 'LAPTOP',  'Dell Latitude 7440',     'jdoe',      DATE '2024-03-11', DATE '2027-03-10', 'IN_USE'),
  ('LAP-1877', 'LAPTOP',  'Lenovo ThinkPad T14',    'asmith',    DATE '2022-06-02', DATE '2025-06-01', 'IN_USE'),
  ('LAP-2310', 'LAPTOP',  'Apple MacBook Pro 14',   'pnair',     DATE '2025-01-20', DATE '2028-01-19', 'IN_USE'),
  ('LAP-1502', 'LAPTOP',  'Dell Latitude 5420',     'mgarcia',   DATE '2021-09-15', DATE '2024-09-14', 'IN_USE'),
  ('LAP-2199', 'LAPTOP',  'HP EliteBook 840',       'tchen',     DATE '2024-08-30', DATE '2027-08-29', 'IN_USE'),
  ('LAP-2205', 'LAPTOP',  'Lenovo ThinkPad X1',     'lkowalski', DATE '2024-09-05', DATE '2027-09-04', 'IN_USE'),
  ('LAP-1950', 'LAPTOP',  'Dell Latitude 7430',     'oadeyemi',  DATE '2023-04-18', DATE '2026-04-17', 'IN_USE'),
  ('LAP-2402', 'LAPTOP',  'Apple MacBook Air 13',   'rsingh',    DATE '2025-05-02', DATE '2028-05-01', 'IN_USE'),
  ('MON-0815', 'MONITOR', 'Dell U2723QE 27"',       'jdoe',      DATE '2024-03-11', DATE '2027-03-10', 'IN_USE'),
  ('LAP-0999', 'LAPTOP',  'Lenovo ThinkPad T14',    NULL,        DATE '2025-02-01', DATE '2028-01-31', 'SPARE');

INSERT INTO ticket (ticket_no, username, asset_tag, category, priority, status, summary, created_at) VALUES
  ('INC-1001', 'asmith',   'LAP-1877', 'HARDWARE', 'MEDIUM', 'OPEN',        'Battery drains within an hour',                  now() - interval '6 days'),
  ('INC-1002', 'pnair',    NULL,       'ACCESS',   'HIGH',   'IN_PROGRESS', 'Cannot access Salesforce after role change',     now() - interval '3 days'),
  ('INC-1003', 'jdoe',     'MON-0815', 'HARDWARE', 'LOW',    'RESOLVED',    'Monitor flickers when docked',                   now() - interval '20 days'),
  ('INC-1004', 'tchen',    NULL,       'SOFTWARE', 'MEDIUM', 'OPEN',        'Adobe licence expired',                          now() - interval '2 days'),
  ('INC-1005', 'oadeyemi', 'LAP-1950', 'NETWORK',  'HIGH',   'OPEN',        'VPN disconnects every 10 minutes',               now() - interval '1 day'),
  ('INC-1006', 'mgarcia',  'LAP-1502', 'HARDWARE', 'MEDIUM', 'OPEN',        'Keyboard keys sticking; laptop out of warranty', now() - interval '4 hours');
