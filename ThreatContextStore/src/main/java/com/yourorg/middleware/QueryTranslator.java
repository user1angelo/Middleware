package com.yourorg.middleware;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Translates JSON query messages into SQL queries for PostgreSQL with JSONB support
 */
public class QueryTranslator {
    
    /**
     * Translates a query JSON message into a SQL query string and parameters
     */
    public static QueryResult translate(JSONObject queryMessage) {
        JSONObject payload = queryMessage.optJSONObject("payload");
        if (payload == null) {
            throw new IllegalArgumentException("Query message must contain a payload");
        }
        
        // Build SELECT clause
        String select = payload.optString("select", "*");
        
        // Build FROM clause
        String from = payload.optString("from", "wazuh_alerts");
        
        // Build WHERE clause from filters
        StringBuilder whereClause = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        
        if (payload.has("filters")) {
            JSONObject filters = payload.getJSONObject("filters");
            Iterator<String> keys = filters.keys();
            
            while (keys.hasNext()) {
                String field = keys.next();
                Object value = filters.get(field);
                
                if (whereClause.length() > 0) {
                    whereClause.append(" AND ");
                }
                
                // Use JSONB operator for payload fields
                whereClause.append("payload->>'").append(field).append("' = ?");
                parameters.add(value.toString());
            }
        }
        
        // Build ORDER BY clause
        String orderBy = payload.optString("order_by", "timestamp");
        String orderDirection = payload.optString("order_direction", "DESC");
        
        // Build LIMIT clause
        int limit = payload.optInt("limit", 100);
        
        // Construct full SQL query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(select);
        sql.append(" FROM ").append(from);
        
        if (whereClause.length() > 0) {
            sql.append(" WHERE ").append(whereClause);
        }
        
        sql.append(" ORDER BY ").append(orderBy).append(" ").append(orderDirection);
        sql.append(" LIMIT ").append(limit);
        
        return new QueryResult(sql.toString(), parameters);
    }
    
    /**
     * Container for SQL query and its parameters
     */
    public static class QueryResult {
        private final String sql;
        private final List<Object> parameters;
        
        public QueryResult(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = parameters;
        }
        
        public String getSql() {
            return sql;
        }
        
        public List<Object> getParameters() {
            return parameters;
        }
    }
}

