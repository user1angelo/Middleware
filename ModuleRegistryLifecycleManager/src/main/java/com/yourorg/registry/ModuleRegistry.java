package com.yourorg.registry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModuleRegistry - Tracks registered User-Defined Modules
 * 
 * Features:
 * - In-memory registry for fast lookups
 * - PostgreSQL persistence for durability across restarts
 * - Health status tracking (online/offline)
 * - Capability-based routing
 * - Manila timezone (GMT+8) timestamps
 */
public class ModuleRegistry {
    
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    
    // In-memory registry: module_id -> RegisteredModule
    private final Map<String, RegisteredModule> modules = new ConcurrentHashMap<>();
    
    /**
     * Registered module data structure
     */
    public static class RegisteredModule {
        private String moduleId;
        private String moduleName;
        private String moduleType;
        private JSONArray capabilities;
        private String commandQueue;
        private Timestamp registeredAt;
        private Timestamp lastHeartbeat;
        private String status; // "online" or "offline"
        private JSONObject metadata;
        
        public RegisteredModule(String moduleId, String moduleName, String moduleType,
                              JSONArray capabilities, String commandQueue, 
                              Timestamp registeredAt, String status, JSONObject metadata) {
            this.moduleId = moduleId;
            this.moduleName = moduleName;
            this.moduleType = moduleType;
            this.capabilities = capabilities;
            this.commandQueue = commandQueue;
            this.registeredAt = registeredAt;
            this.lastHeartbeat = registeredAt;
            this.status = status;
            this.metadata = metadata;
        }
        
        // Getters
        public String getModuleId() { return moduleId; }
        public String getModuleName() { return moduleName; }
        public String getModuleType() { return moduleType; }
        public JSONArray getCapabilities() { return capabilities; }
        public String getCommandQueue() { return commandQueue; }
        public Timestamp getRegisteredAt() { return registeredAt; }
        public Timestamp getLastHeartbeat() { return lastHeartbeat; }
        public String getStatus() { return status; }
        public JSONObject getMetadata() { return metadata; }
        
        // Setters
        public void setLastHeartbeat(Timestamp lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public void setStatus(String status) { this.status = status; }
        
        public boolean hasCapability(String capability) {
            for (int i = 0; i < capabilities.length(); i++) {
                if (capabilities.getString(i).equals(capability)) {
                    return true;
                }
            }
            return false;
        }
    }
    
    /**
     * Register a new module (or update existing)
     */
    public void registerModule(JSONObject registration) {
        try {
            JSONObject payload = registration.getJSONObject("payload");
            
            String moduleId = payload.getString("module_id");
            String moduleName = payload.getString("module_name");
            String moduleType = payload.getString("module_type");
            JSONArray capabilities = payload.getJSONArray("capabilities");
            String commandQueue = payload.getString("command_queue");
            JSONObject metadata = payload.optJSONObject("metadata");
            if (metadata == null) metadata = new JSONObject();
            
            Timestamp now = getCurrentManilaTimestamp();
            
            // Check if module already exists
            if (modules.containsKey(moduleId)) {
                System.out.println("🔄 Module already registered, updating: " + moduleId);
                updateModule(moduleId, now, "online");
            } else {
                System.out.println("📝 Registering new module: " + moduleId);
                
                RegisteredModule module = new RegisteredModule(
                    moduleId, moduleName, moduleType, capabilities, 
                    commandQueue, now, "online", metadata
                );
                
                // Store in memory
                modules.put(moduleId, module);
                
                // Store in database
                saveModuleToDatabase(module);
                
                System.out.println("✅ Module registered successfully: " + moduleName);
                System.out.println("   Type: " + moduleType);
                System.out.println("   Capabilities: " + capabilities);
                System.out.println("   Command Queue: " + commandQueue);
            }
            
        } catch (Exception e) {
            System.err.println("❌ Failed to register module: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Update module heartbeat
     */
    public void updateHeartbeat(String moduleId) {
        RegisteredModule module = modules.get(moduleId);
        if (module != null) {
            Timestamp now = getCurrentManilaTimestamp();
            module.setLastHeartbeat(now);
            
            // Mark as online if it was offline
            if ("offline".equals(module.getStatus())) {
                module.setStatus("online");
                System.out.println("🟢 Module back online: " + moduleId);
            }
            
            // Update in database
            updateModule(moduleId, now, "online");
        } else {
            System.out.println("⚠️  Heartbeat from unregistered module: " + moduleId);
        }
    }
    
    /**
     * Mark module as offline
     */
    public void markModuleOffline(String moduleId) {
        RegisteredModule module = modules.get(moduleId);
        if (module != null) {
            module.setStatus("offline");
            updateModuleStatus(moduleId, "offline");
            System.out.println("🔴 Module marked offline: " + moduleId);
        }
    }
    
    /**
     * Find module by capability
     */
    public RegisteredModule findModuleByCapability(String capability) {
        for (RegisteredModule module : modules.values()) {
            if ("online".equals(module.getStatus()) && module.hasCapability(capability)) {
                return module;
            }
        }
        return null;
    }
    
    /**
     * Get all registered modules
     */
    public Map<String, RegisteredModule> getAllModules() {
        return modules;
    }
    
    /**
     * Get module by ID
     */
    public RegisteredModule getModule(String moduleId) {
        return modules.get(moduleId);
    }
    
    /**
     * Save module to database
     */
    private void saveModuleToDatabase(RegisteredModule module) {
        String sql = "INSERT INTO registered_modules " +
                    "(module_id, module_name, module_type, capabilities, command_queue, " +
                    "registered_at, last_heartbeat, status, metadata) " +
                    "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb) " +
                    "ON CONFLICT (module_id) DO UPDATE SET " +
                    "last_heartbeat = EXCLUDED.last_heartbeat, " +
                    "status = EXCLUDED.status";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, module.getModuleId());
            stmt.setString(2, module.getModuleName());
            stmt.setString(3, module.getModuleType());
            stmt.setString(4, module.getCapabilities().toString());
            stmt.setString(5, module.getCommandQueue());
            stmt.setTimestamp(6, module.getRegisteredAt());
            stmt.setTimestamp(7, module.getLastHeartbeat());
            stmt.setString(8, module.getStatus());
            stmt.setString(9, module.getMetadata().toString());
            
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            System.err.println("❌ Failed to save module to database: " + e.getMessage());
        }
    }
    
    /**
     * Update module heartbeat in database
     */
    private void updateModule(String moduleId, Timestamp timestamp, String status) {
        String sql = "UPDATE registered_modules SET last_heartbeat = ?, status = ? WHERE module_id = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setTimestamp(1, timestamp);
            stmt.setString(2, status);
            stmt.setString(3, moduleId);
            
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            System.err.println("❌ Failed to update module: " + e.getMessage());
        }
    }
    
    /**
     * Update module status in database
     */
    private void updateModuleStatus(String moduleId, String status) {
        String sql = "UPDATE registered_modules SET status = ? WHERE module_id = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, status);
            stmt.setString(2, moduleId);
            
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            System.err.println("❌ Failed to update module status: " + e.getMessage());
        }
    }
    
    /**
     * Load modules from database on startup
     */
    public void loadModulesFromDatabase() {
        String sql = "SELECT * FROM registered_modules";
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            int count = 0;
            while (rs.next()) {
                String moduleId = rs.getString("module_id");
                String moduleName = rs.getString("module_name");
                String moduleType = rs.getString("module_type");
                String capabilitiesJson = rs.getString("capabilities");
                String commandQueue = rs.getString("command_queue");
                Timestamp registeredAt = rs.getTimestamp("registered_at");
                Timestamp lastHeartbeat = rs.getTimestamp("last_heartbeat");
                String status = rs.getString("status");
                String metadataJson = rs.getString("metadata");
                
                JSONArray capabilities = new JSONArray(capabilitiesJson);
                JSONObject metadata = new JSONObject(metadataJson != null ? metadataJson : "{}");
                
                RegisteredModule module = new RegisteredModule(
                    moduleId, moduleName, moduleType, capabilities,
                    commandQueue, registeredAt, status, metadata
                );
                module.setLastHeartbeat(lastHeartbeat);
                
                modules.put(moduleId, module);
                count++;
            }
            
            System.out.println("📂 Loaded " + count + " modules from database");
            
        } catch (SQLException e) {
            System.err.println("❌ Failed to load modules from database: " + e.getMessage());
        }
    }
    
    /**
     * Get database connection
     */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
            ConfigLoader.getDbUrl(),
            ConfigLoader.getDbUser(),
            ConfigLoader.getDbPassword()
        );
    }
    
    /**
     * Get current Manila time as Timestamp
     */
    private Timestamp getCurrentManilaTimestamp() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return Timestamp.from(manilaTime.toInstant());
    }
    
    /**
     * Get current Manila time as ISO string
     */
    public static String getCurrentManilaTime() {
        ZonedDateTime manilaTime = ZonedDateTime.now(MANILA_ZONE);
        return manilaTime.format(ISO_FORMATTER);
    }
}

