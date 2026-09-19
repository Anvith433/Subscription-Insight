-- Standardizing the 'source' column to handle the DataSource Enum strings
-- We ensure the columns are VARCHAR(50) so they can store 'ANDROID', 'SYSTEM', etc.

ALTER TABLE user_snapshots 
MODIFY COLUMN source VARCHAR(50) NOT NULL;

ALTER TABLE billing_records 
MODIFY COLUMN source VARCHAR(50) NOT NULL;