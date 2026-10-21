-- ---------------------------------------------------------------------------
-- Business meaning for the Text-to-SQL demo (demo 10). The schema prompt is
-- built from these comments, so the model knows what columns mean and which
-- values they hold, the way a semantic layer does in BI tools.
-- ---------------------------------------------------------------------------
COMMENT ON TABLE employee IS 'Acme Corp employees. One row per person.';
COMMENT ON COLUMN employee.username   IS 'Login name, lowercase, e.g. jdoe. Primary business key; referenced by asset.assigned_to and ticket.username.';
COMMENT ON COLUMN employee.full_name  IS 'Display name, e.g. John Doe.';
COMMENT ON COLUMN employee.email      IS 'Work email address.';
COMMENT ON COLUMN employee.department IS 'One of Engineering, Finance, Sales, Marketing, HR, Support, Operations.';
COMMENT ON COLUMN employee.location   IS 'Office city, e.g. Berlin, London, Bangalore.';
COMMENT ON COLUMN employee.manager    IS 'Username of the line manager (references employee.username); NULL for top managers.';

COMMENT ON TABLE asset IS 'IT hardware owned by Acme (laptops, monitors).';
COMMENT ON COLUMN asset.asset_tag      IS 'Inventory tag, e.g. LAP-2041 or MON-0815.';
COMMENT ON COLUMN asset.type           IS 'LAPTOP or MONITOR.';
COMMENT ON COLUMN asset.model          IS 'Vendor model name, e.g. Dell Latitude 7440.';
COMMENT ON COLUMN asset.assigned_to    IS 'Username of the employee using the asset (references employee.username); NULL for spare stock.';
COMMENT ON COLUMN asset.purchased_on   IS 'Purchase date.';
COMMENT ON COLUMN asset.warranty_until IS 'Last day of the vendor warranty. Out of warranty means warranty_until < CURRENT_DATE.';
COMMENT ON COLUMN asset.status         IS 'IN_USE, SPARE or LOANED.';

COMMENT ON TABLE ticket IS 'IT helpdesk tickets.';
COMMENT ON COLUMN ticket.ticket_no  IS 'Ticket number, e.g. INC-1001.';
COMMENT ON COLUMN ticket.username   IS 'Employee who raised the ticket (references employee.username).';
COMMENT ON COLUMN ticket.asset_tag  IS 'Affected device (references asset.asset_tag); NULL if no device is involved.';
COMMENT ON COLUMN ticket.category   IS 'HARDWARE, SOFTWARE, NETWORK or ACCESS.';
COMMENT ON COLUMN ticket.priority   IS 'LOW, MEDIUM or HIGH.';
COMMENT ON COLUMN ticket.status     IS 'OPEN, IN_PROGRESS or RESOLVED. Open work means status <> ''RESOLVED''.';
COMMENT ON COLUMN ticket.summary    IS 'One-sentence problem description.';
COMMENT ON COLUMN ticket.created_at IS 'When the ticket was created.';
