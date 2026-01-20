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
     * Initialize modules on startup.
     *
     * The filesystem (user-defined-modules) is the source of truth for which
     * modules exist. The database is used only to enrich those modules with
     * historical state (last_heartbeat, status, metadata) when available.
     */
    public void loadModulesFromDatabase() {
        // Always start from a clean in-memory view
        modules.clear();

        // 1) Scan filesystem to discover which modules exist and upsert them
        //    into the database as needed.
        try {
            scanModulesFromFilesystem();
        } catch (Exception e) {
            System.err.println("⚠️  Failed to scan user-defined-modules directory: " + e.getMessage());
            e.printStackTrace();
        }

        // 2) Load existing DB state only for modules we already know from
        //    the filesystem (do NOT resurrect old/stale module_ids that
        //    have no corresponding directory/config any more).
        String sql = "SELECT * FROM registered_modules";
        int updated = 0;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String moduleId = rs.getString("module_id");

                // Only consider modules that exist on disk
                RegisteredModule existing = modules.get(moduleId);
                if (existing == null) {
                    continue; // skip stale DB-only records
                }

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

                RegisteredModule enriched = new RegisteredModule(
                    moduleId,
                    moduleName != null ? moduleName : existing.getModuleName(),
                    moduleType != null ? moduleType : existing.getModuleType(),
                    capabilities,
                    commandQueue != null ? commandQueue : existing.getCommandQueue(),
                    registeredAt != null ? registeredAt : existing.getRegisteredAt(),
                    status != null ? status : existing.getStatus(),
                    metadata.length() > 0 ? metadata : existing.getMetadata()
                );
                enriched.setLastHeartbeat(lastHeartbeat != null ? lastHeartbeat : existing.getLastHeartbeat());

                modules.put(moduleId, enriched);
                updated++;
            }

            System.out.println("📂 Filesystem modules initialized: " + modules.size());
            System.out.println("📂 Enriched from database: " + updated + " modules");

        } catch (SQLException e) {
            System.err.println("❌ Failed to load modules from database: " + e.getMessage());
        }
    }
    
/**
     * Scan the filesystem-based user-defined-modules directory and ensure
     * there is at least a placeholder record for each discovered JAR plugin.
     *
     * Conventions:
     *   - modules.root (from ConfigLoader) points at the root directory, e.g.
     *       ../user-defined-modules
     *   - The root itself contains one or more `*.jar` plugin files; the
     *     base filename (without .jar) is treated as `module_id`.
     *   - Optionally, a matching config file may exist under
     *       config/<module_id>.properties
     *
     * This does NOT start any processes. It only ensures that a basic
     * registered_modules row exists so the dashboard can see that the
     * module is known, even before it has sent a registration message or
     * heartbeats. The set of JARs under modules.root is the source of truth
     * for which module IDs exist.
     */
    public void scanModulesFromFilesystem() {
        String root = ConfigLoader.getModulesRoot();
        java.io.File rootDir = new java.io.File(root);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.out.println("ℹ️  modules.root does not exist or is not a directory: " + root);
            return;
        }

        int created = 0;

        java.io.File configDir = new java.io.File(rootDir, "config");

        // Discover modules via *.jar plugin files under modules.root
        java.io.File[] jarFiles = rootDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles != null && jarFiles.length > 0) {
            for (java.io.File jar : jarFiles) {
                String filename = jar.getName();
                String moduleId = filename.replaceFirst("\\.jar$", "");

                // Skip if already in memory (from DB or runtime registration)
                if (modules.containsKey(moduleId)) {
                    continue;
                }

                try {
                    System.out.println("🧩 Found JAR plugin: " + filename + " (module_id=" + moduleId + ")");

                    JSONArray capabilities = new JSONArray();
                    JSONObject metadata = new JSONObject();
                    metadata.put("source", "jar");
                    metadata.put("jar_path", jar.getAbsolutePath());

                    // Attach config path if a matching properties file exists
                    if (configDir.exists() && configDir.isDirectory()) {
                        java.io.File cfg = new java.io.File(configDir, moduleId + ".properties");
                        if (cfg.exists() && cfg.isFile()) {
                            metadata.put("config_path", cfg.getAbsolutePath());
                        }
                    }

                    Timestamp now = getCurrentManilaTimestamp();
                    RegisteredModule module = new RegisteredModule(
                        moduleId,
                        moduleId,               // use id as name by default
                        "generic_udm",         // generic type (can be refined later)
                        capabilities,
                        moduleId + "_commands_queue", // default command queue naming convention
                        now,
                        "offline",             // until registration/heartbeat
                        metadata
                    );

                    modules.put(moduleId, module);
                    saveModuleToDatabase(module);
                    created++;

                } catch (Exception e) {
                    System.err.println("❌ Failed to create placeholder for JAR plugin " + filename + ": " + e.getMessage());
                }
            }
        } else {
            System.out.println("ℹ️  No *.jar plugin files found under " + rootDir.getAbsolutePath());
        }

        if (created > 0) {
            System.out.println("📁 Registered or updated " + created + " JAR modules from " + rootDir.getAbsolutePath());
        } else {
            System.out.println("ℹ️  No new JAR modules to register (all already known).");
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

