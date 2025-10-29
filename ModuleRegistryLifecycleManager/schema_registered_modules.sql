-- Schema for ModuleRegistry - registered_modules table
-- This table tracks User-Defined Modules (UDMs) registered with the system
-- 
-- Add this to the existing wazuhdb database used by ThreatContextStore

-- Connect to wazuhdb
\c wazuhdb

-- Create registered_modules table
CREATE TABLE IF NOT EXISTS registered_modules (
    module_id VARCHAR(255) PRIMARY KEY,
    module_name VARCHAR(255) NOT NULL,
    module_type VARCHAR(100) NOT NULL,
    capabilities JSONB NOT NULL,
    command_queue VARCHAR(255) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    last_heartbeat TIMESTAMPTZ,
    status VARCHAR(50) DEFAULT 'offline',
    metadata JSONB,
    
    CONSTRAINT status_check CHECK (status IN ('online', 'offline'))
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_registered_modules_status ON registered_modules(status);
CREATE INDEX IF NOT EXISTS idx_registered_modules_type ON registered_modules(module_type);
CREATE INDEX IF NOT EXISTS idx_registered_modules_capabilities ON registered_modules USING GIN(capabilities);
CREATE INDEX IF NOT EXISTS idx_registered_modules_heartbeat ON registered_modules(last_heartbeat);

-- Add comments
COMMENT ON TABLE registered_modules IS 'Tracks User-Defined Modules registered with ModuleRegistry';
COMMENT ON COLUMN registered_modules.module_id IS 'Unique identifier for the module';
COMMENT ON COLUMN registered_modules.module_name IS 'Human-readable module name';
COMMENT ON COLUMN registered_modules.module_type IS 'Module category (e.g., network_isolation, threat_intel)';
COMMENT ON COLUMN registered_modules.capabilities IS 'Array of actions this module can perform (e.g., ["sdn_isolate", "firewall_block"])';
COMMENT ON COLUMN registered_modules.command_queue IS 'RabbitMQ queue name for sending commands to this module';
COMMENT ON COLUMN registered_modules.registered_at IS 'When the module first registered (Manila timezone)';
COMMENT ON COLUMN registered_modules.last_heartbeat IS 'Last heartbeat received from module (Manila timezone)';
COMMENT ON COLUMN registered_modules.status IS 'Current module status: online or offline';
COMMENT ON COLUMN registered_modules.metadata IS 'Additional module-specific metadata';

-- Grant permissions
GRANT ALL PRIVILEGES ON TABLE registered_modules TO postgres;

-- Display table structure
\d registered_modules

-- Sample query to view registered modules
SELECT 
    module_id, 
    module_name, 
    module_type, 
    status,
    capabilities,
    last_heartbeat,
    EXTRACT(EPOCH FROM (NOW() - last_heartbeat)) / 60 AS minutes_since_heartbeat
FROM registered_modules
ORDER BY status DESC, last_heartbeat DESC;

