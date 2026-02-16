# WorkflowEngine Quick Start

WorkflowEngine listens for ransomware-related `alert` messages on a RabbitMQ queue, matches them against YAML workflows, and publishes:

- **Commands** to `rabbitmq.workflow_command_queue.name` (default: `workflow_command_queue`)
- **Execution status** to `rabbitmq.workflow_response_queue.name` (default: `workflow_response_queue`)

## 1) Configure

From the repo root:

```bash
cd WorkflowEngine
```

Edit `WorkflowEngine/config.properties` (or rely on defaults in `src/main/java/com/yourorg/workflow/ConfigLoader.java`).

Important keys used by WorkflowEngine:
- `rabbitmq.host`, `rabbitmq.port`, `rabbitmq.user`, `rabbitmq.password`
- `rabbitmq.workflow_queue.name`
- `rabbitmq.workflow_command_queue.name`
- `rabbitmq.workflow_response_queue.name`
- `workflows.directory`

## 2) Ensure workflows exist

WorkflowEngine loads `*.yml` / `*.yaml` from `workflows.directory` (default: `workflows/ransomware`).

## 3) Compile (javac)

```bash
cd WorkflowEngine

# Windows
javac -cp "lib/*;out" -d out src/main/java/com/yourorg/workflow/*.java

# Linux/macOS
# javac -cp "lib/*:out" -d out src/main/java/com/yourorg/workflow/*.java
```

## 4) Run

```bash
cd WorkflowEngine

# Windows
java -cp "out;lib/*" com.yourorg.workflow.WorkflowEngineMain

# Linux/macOS
# java -cp "out:lib/*" com.yourorg.workflow.WorkflowEngineMain
```

## 5) Test (optional)

You can send test alerts and monitor output queues using the included tester:

```bash
cd WorkflowEngine

# Windows
java -cp "out;lib/*" WorkflowTester

# Linux/macOS
# java -cp "out:lib/*" WorkflowTester
```

In the tester menu, use option **3** to monitor both `workflow_command_queue` and `workflow_response_queue`.

