package com.yourorg.middleware;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

public class WazuhAlertDao {

    private static final String DB_URL = ConfigLoader.getDbUrl();
    private static final String DB_USER = ConfigLoader.getDbUser();
    private static final String DB_PASSWORD = ConfigLoader.getDbPassword();

    /**
     * Insert message (alert or query) if event_id does not exist
     */
    public void insertMessage(JSONObject message) throws Exception {
        Class.forName("org.postgresql.Driver");

        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String eventIdStr = message.optString("event_id", UUID.randomUUID().toString());
            
            // Handle query IDs with "query-" prefix
            UUID eventId;
            if (eventIdStr.startsWith("query-")) {
                // Remove "query-" prefix and parse the UUID
                eventId = UUID.fromString(eventIdStr.substring(6));
            } else {
                eventId = UUID.fromString(eventIdStr);
            }

            // Check duplicate
            String checkSql = "SELECT 1 FROM wazuh_alerts WHERE event_id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setObject(1, eventId);
                ResultSet rs = checkStmt.executeQuery();
                if (rs.next()) {
                    return; // Already exists, skip
                }
            }

            // Insert message
            String insertSql = "INSERT INTO wazuh_alerts (event_id, message_type, timestamp, event_type, source_module, payload, response_count, response_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setObject(1, eventId);
                stmt.setString(2, message.optString("message_type", "alert"));

                // Timestamp
                String tsStr = message.optString("timestamp", "");
                Timestamp timestamp;
                if (!tsStr.isEmpty()) {
                    timestamp = Timestamp.from(java.time.Instant.parse(tsStr));
                } else {
                    timestamp = new Timestamp(System.currentTimeMillis());
                }
                stmt.setTimestamp(3, timestamp);

                stmt.setString(4, message.optString("event_type", "unknown"));
                stmt.setString(5, message.optString("source_module", "unknown"));

                // Payload JSON
                JSONObject payload = message.optJSONObject("payload");
                if (payload == null) {
                    payload = new JSONObject();
                }
                stmt.setObject(6, payload.toString(), java.sql.Types.OTHER);

                // Query-specific fields
                stmt.setInt(7, 0); // response_count
                stmt.setString(8, "pending"); // response_status

                stmt.executeUpdate();
            }
        }
    }

    /**
     * Update query message with response summary
     */
    public void updateQueryResponse(UUID queryId, int responseCount, String status) throws Exception {
        Class.forName("org.postgresql.Driver");

        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String updateSql = "UPDATE wazuh_alerts SET response_count = ?, response_status = ? WHERE event_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setInt(1, responseCount);
                stmt.setString(2, status);
                stmt.setObject(3, queryId);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * Execute a dynamic SQL query and return results
     */
    public List<JSONObject> executeQuery(String sql, List<Object> parameters) throws Exception {
        List<JSONObject> results = new ArrayList<>();
        Class.forName("org.postgresql.Driver");

        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Set parameters
            for (int i = 0; i < parameters.size(); i++) {
                stmt.setObject(i + 1, parameters.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject result = new JSONObject();
                    result.put("message_type", rs.getString("message_type"));
                    result.put("event_id", rs.getObject("event_id").toString());
                    result.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                    result.put("event_type", rs.getString("event_type"));
                    result.put("source_module", rs.getString("source_module"));
                    result.put("payload", new JSONObject(rs.getString("payload")));
                    results.add(result);
                }
            }
        }

        return results;
    }

    /**
     * Query alerts by severity (legacy method for backward compatibility)
     */
    public List<JSONObject> queryBySeverityList(String severity) throws Exception {
        String sql = "SELECT * FROM wazuh_alerts WHERE message_type = 'alert' AND payload->>'severity' = ? ORDER BY timestamp DESC";
        List<Object> params = new ArrayList<>();
        params.add(severity);
        return executeQuery(sql, params);
    }
}
