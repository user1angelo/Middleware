DROP TABLE IF EXISTS wazuh_alerts;

CREATE TABLE wazuh_alerts (
    log_id TEXT PRIMARY KEY,        -- new unique key: tsWithMillis_eventType_UUID
    event_id TEXT NOT NULL,         -- original event_id from JSON
    timestamp TIMESTAMPTZ NOT NULL,
    event_type TEXT NOT NULL,
    source_module TEXT NOT NULL,
    payload JSONB NOT NULL
);

GRANT ALL PRIVILEGES ON TABLE wazuh_alerts TO alerts_user;
