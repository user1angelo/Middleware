package com.yourorg.middleware;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

public class WazuhAlertDao {

    private static final String DB_URL = "jdbc:postgresql://192.168.86.28:5432/wazuhdb";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    // Insert alert if event_id does not exist
    public void insertAlert(JSONObject alert) throws Exception {
        Class.forName("org.postgresql.Driver");

        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {

            // Check duplicate
            String checkSql = "SELECT 1 FROM public.wazuh_alerts WHERE event_id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                String eventIdStr = alert.optString("event_id", UUID.randomUUID().toString());
                UUID eventId = UUID.fromString(eventIdStr);
                checkStmt.setObject(1, eventId);
                ResultSet rs = checkStmt.executeQuery();
                if (rs.next()) {
                    // Event exists, skip
                    return;
                }
            }

            // Insert alert
            String insertSql = "INSERT INTO public.wazuh_alerts (event_id, timestamp, event_type, source_module, payload) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                String eventIdStr = alert.optString("event_id", UUID.randomUUID().toString());
                stmt.setObject(1, UUID.fromString(eventIdStr));

                // Timestamp
                String tsStr = alert.optString("timestamp", "");
                Timestamp timestamp;
                if (!tsStr.isEmpty()) {
                    timestamp = Timestamp.from(java.time.Instant.parse(tsStr));
                } else {
                    timestamp = new Timestamp(System.currentTimeMillis());
                }
                stmt.setTimestamp(2, timestamp);

                stmt.setString(3, alert.optString("event_type", "unknown"));
                stmt.setString(4, alert.optString("source_module", "unknown"));

                // Payload JSON
                JSONObject payload = alert.optJSONObject("payload") != null ? alert.getJSONObject("payload") : new JSONObject();
                stmt.setObject(5, payload.toString(), java.sql.Types.OTHER);

                stmt.executeUpdate();
            }
        }
    }

    // Query alerts by severity and return list of full JSON
    public List<JSONObject> queryBySeverityList(String severity) throws Exception {
        List<JSONObject> alerts = new ArrayList<>();
        Class.forName("org.postgresql.Driver");

        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM public.wazuh_alerts WHERE payload->>'severity' = ? ORDER BY timestamp DESC")) {

            stmt.setString(1, severity);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject alert = new JSONObject();
                    alert.put("event_id", rs.getObject("event_id").toString());
                    alert.put("timestamp", rs.getTimestamp("timestamp").toInstant().toString());
                    alert.put("event_type", rs.getString("event_type"));
                    alert.put("source_module", rs.getString("source_module"));
                    alert.put("payload", new JSONObject(rs.getString("payload")));
                    alerts.add(alert);
                }
            }
        }

        return alerts;
    }
}
