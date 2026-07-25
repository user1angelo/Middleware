DROP TABLE IF EXISTS alerts;

CREATE TABLE alerts (
    event_id UUID PRIMARY KEY,              -- unique event identifier
    message_type VARCHAR(50) NOT NULL,      -- message type: alert, query, query_response
    timestamp TIMESTAMPTZ NOT NULL,         -- event timestamp
    event_type VARCHAR(255) NOT NULL,       -- event classification
    source_module VARCHAR(255) NOT NULL,    -- source system identifier
    payload JSONB NOT NULL,                 -- flexible JSON data
    response_count INTEGER DEFAULT 0,       -- for queries: number of responses sent
    response_status VARCHAR(50) DEFAULT 'pending'  -- for queries: success, failed, pending
);

-- Create indexes for better query performance
CREATE INDEX idx_alerts_message_type ON alerts(message_type);
CREATE INDEX idx_alerts_timestamp ON alerts(timestamp);
CREATE INDEX idx_alerts_payload ON alerts USING GIN(payload);

GRANT ALL PRIVILEGES ON TABLE alerts TO postgres;
