-- Migration: Add response_count and response_status columns
-- Run this on your PostgreSQL database server (192.168.86.28)
--
-- Usage:
--   psql -U postgres -d wazuhdb -f migration_add_query_columns.sql

\c wazuhdb

-- Add missing columns if they don't exist
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS response_count INTEGER DEFAULT 0;
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS response_status VARCHAR(50) DEFAULT 'pending';

-- Verify the columns were added
\d alerts

SELECT 'Migration completed successfully!' AS status;
