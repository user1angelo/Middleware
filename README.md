# Middleware Project

## 🚀 Project Overview

This Middleware project is a comprehensive security orchestration and automated response platform designed to bridge the gap between detection (Wazuh), analysis (ThreatContextStore), and response (WorkflowEngine + SDN).

### 🔑 Key Components

| Component | Description | Location |
|-----------|-------------|----------|
| **ThreatContextStore** | Ingests alerts from Wazuh, stores them in PostgreSQL, and handles queries. | `/ThreatContextStore` |
| **WorkflowEngine** | Orchestrates response actions based on YAML workflow definitions triggered by alerts. | `/WorkflowEngine` |
| **OpenDaylight SDK Module** | Canonical SDN enforcement module loaded by ModuleRegistry/Lifecycle Manager. | `/user-defined-modules/src/main/java/com/nis1/thesis/udm/OpenDaylightModule.java` |
| **Web Dashboard** | Visualization interface for alerts and system status. | `/webapp` |
| **NIS Thesis SDK** | Shared library for common data models and utilities. | `/nis-thesis-sdk` |

---

## 🏗️ Architecture

The system follows an event-driven microservices architecture:

```mermaid
graph TD
    A[Wazuh/Detector] -->|Alerts| B(RabbitMQ: alerts_queue)
    B --> C[ThreatContextStore]
    B --> D[WorkflowEngine]
    C -->|Store| E[(PostgreSQL)]
    D -->|Commands| F(RabbitMQ: workflow_response_queue)
    F --> G[ModuleRegistry + SdkModuleHost]
    G --> H[OpenDaylightModule (SDK)]
    H -->|RESTCONF/OpenFlow| I[Network Switches]
```

### Data Flow
1. **Detection**: Alerts are published to `alerts_queue`.
2. **Analysis**: `ThreatContextStore` consumes alerts for storage and historical analysis.
3. **Orchestration**: `WorkflowEngine` consumes the same alerts, matches them against Security Playbooks (YAML), and decides on a course of action.
4. **Enforcement**: If an action is required (e.g., "Isolate Host"), a command is sent to `workflow_response_queue`.
5. **Execution**: `ModuleRegistry` routes the command to the SDK `OpenDaylightModule`, which applies SDN flow rules.

---

## 📚 Documentation Index

- **[Quick Start Guide](QUICKSTART.md)**: fast track to getting the system up and running.
- **[Workflow Engine Guide](WorkflowEngine/WORKFLOW_ENGINE_COMPLETE.md)**: Detailed documentation on creating custom workflows and the engine internals.
- **[Threat Context Store Guide](ThreatContextStore/ThreatContextStoreGuide.md)**: Deep dive into the storage and query system.
- **[Web Dashboard Guide](webapp/README.md)**: Instructions for running the frontend.
- **[Troubleshooting](DEBUGGING.md)**: Common issues and solutions.

---

## 🛠️ Development Setup

### Prerequisites
- **Java**: JDK 17+ (JDK 21 for SDK)
- **Maven**: 3.8+
- **PostgreSQL**: 14+
- **RabbitMQ**: 3.9+
- **OpenDaylight**: Carbon (for Enforcer module) or header-compatible version.

### Build All Components
```bash
# 1. Install SDK
cd nis-thesis-sdk
mvn clean install

# 2. Build ThreatContextStore (javac-based)
cd ../ThreatContextStore
# Linux/macOS: use ':' as classpath separator
# Windows: use ';' as classpath separator
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/middleware/*.java

# 3. Build WorkflowEngine (javac-based)
cd ../WorkflowEngine
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/workflow/*.java

# 4. Build OpenDaylight SDK module
cd ../user-defined-modules
powershell -ExecutionPolicy Bypass -File .\compile_odl.ps1
```

---

## 🧪 Testing

### Running Unit Tests
Each module has its own test suite.
- **SDK module host/router**: compile check via `javac` in `ModuleRegistryLifecycleManager`
- **OpenDaylight SDK module**: rebuild via `user-defined-modules/compile_odl.ps1`

### System Verification
Use the `ThreatContextStoreTester` to generate synthetic alerts:
```bash
cd ThreatContextStoreTester
java -cp ".:../ThreatContextStore/lib/*" TCSTester
```

Select "Send random alerts" to trigger the entire pipeline.
