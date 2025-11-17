# Developer Guide — Middleware Web Dashboard

This guide helps you understand the web dashboard and shows exactly how to extend and improve it. It complements the top‑level README with developer‑focused details.

## Tech Stack

- Frontend: React (CRA), React Router, Axios, Socket.IO client
- Backend: Node.js (Express), Socket.IO, amqplib (RabbitMQ), pg (PostgreSQL), js-yaml
- Realtime: WebSocket via Socket.IO
- Data sources: RabbitMQ queues, PostgreSQL `registered_modules` table, local filesystem (configs and workflows)

Directory layout (abridged):
```
webapp/
├── backend/
│   ├── server.js
│   └── services/
│       ├── processManager.js
│       ├── logService.js
│       ├── configService.js
│       ├── workflowService.js
│       └── moduleHealthService.js
└── frontend/
    ├── public/index.html
    └── src/
        ├── App.js
        ├── components/Layout.js
        ├── pages/{Home,ControlPanel,Logs,Workflows,Configuration,Modules,Testing}.js
        └── services/api.js
```

## Quickstart for Development

1) Backend
```bash
cd webapp/backend
cp .env.example .env   # create this file — see template below
npm install
npm run dev            # starts on http://localhost:3001
```

2) Frontend
```bash
cd webapp/frontend
npm install
npm start              # opens http://localhost:3000
```

### Backend .env template
Create `webapp/backend/.env` with the following variables (adjust paths and creds):

```
# Server
PORT=3001
MIDDLEWARE_ROOT=/home/you/Documents/GitHub/Middleware
LOGS_DIR=/home/you/Documents/GitHub/Middleware/webapp/logs

# Java program locations
THREAT_CONTEXT_STORE_PATH=/home/you/Documents/GitHub/Middleware/ThreatContextStore
MODULE_REGISTRY_PATH=/home/you/Documents/GitHub/Middleware/ModuleRegistry
WORKFLOW_ENGINE_PATH=/home/you/Documents/GitHub/Middleware/WorkflowEngine
TCS_TESTER_PATH=/home/you/Documents/GitHub/Middleware/TCSTester

# Optional: OpenDaylight SDN controller (managed from Control Panel)
# If you don't use OpenDaylight, you can omit these.
OPENDAYLIGHT_PATH=/opt/opendaylight
OPENDAYLIGHT_COMMAND=/opt/opendaylight/bin/start
OPENDAYLIGHT_ARGS=

# PostgreSQL (used by moduleHealthService)
DB_HOST=localhost
DB_PORT=5432
DB_NAME=wazuhdb
DB_USER=postgres
DB_PASSWORD=postgres

# RabbitMQ (used by logService)
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest
```

Frontend API base is currently hardcoded to `http://localhost:3001/api` in `frontend/src/services/api.js`. See “Improvements” to make this configurable.

## How Things Fit Together

- Backend `server.js`
  - REST routes under `/api/*`
  - Sets up Socket.IO and passes it to `logService` for realtime events
  - CORS currently allows `http://localhost:3000`

- Services
  - `processManager.js`: starts/stops the Java programs; streams stdout/stderr to WebSocket and to log files under `LOGS_DIR`.
  - `configService.js`: reads/writes each program’s `config.properties`, creating timestamped backups on write.
  - `workflowService.js`: CRUD for YAML workflows under `${WORKFLOW_ENGINE_PATH}/workflows/<category>/*.yml` (backs up deletes under `${MIDDLEWARE_ROOT}/webapp/backups`).
  - `moduleHealthService.js`: queries the `registered_modules` table and summarizes health.
  - `logService.js`: connects to RabbitMQ and broadcasts messages from specific queues as `rabbitmq-log` events.

- Frontend
  - `src/App.js` defines the router. Pages call the API via `src/services/api.js` and subscribe to Socket.IO for live logs.
  - `pages/Home.js` demonstrates consuming both REST and WebSocket.

## API Contract (summary)
- Processes: `GET /api/processes/status`, `POST /api/processes/:name/start`, `POST /api/processes/:name/stop`, `POST /api/processes/test`
- Config: `GET /api/config/:program`, `PUT /api/config/:program`
- Workflows: `GET /api/workflows`, `GET /api/workflows/:category/:name`, `POST /api/workflows/:category`, `PUT /api/workflows/:category/:name`, `DELETE /api/workflows/:category/:name`
- Modules: `GET /api/modules/health`

See `webapp/README.md` for more detail.

## Adding Features — Step by Step

### Add a new backend endpoint
1) Create the logic in a service or a new file under `backend/services/`.
2) Register a route in `backend/server.js` under the appropriate section.
3) Return JSON with `{ success, ... }` and useful error messages; use `try/catch` with `res.status(500)` on failure.
4) If the feature needs realtime, emit via `io.emit()` (see `logService.broadcastLog`).
5) Add a matching function in `frontend/src/services/api.js` and consume it from a page.

### Add support for another managed process
1) Edit `backend/services/processManager.js` and add an entry to the `PROCESSES` map:
   - `name`, `cwd`, `command`, `args`
2) Ensure the program path exists in `.env` (e.g., `FOO_PATH`) and that Java classpath is correct.
3) Frontend: surface it on the Control Panel page so users can start/stop it.

### Add a new page to the UI
1) Create `frontend/src/pages/MyFeature.js` and implement the component.
2) Add a `<Route path="/my-feature" element={<MyFeature/>} />` in `frontend/src/App.js`.
3) Add a link in `frontend/src/components/Layout.js`.
4) Fetch data via `frontend/src/services/api.js` and handle loading/error states.

### Extend module health
- Ensure the PostgreSQL table `registered_modules` has at minimum columns:
  `module_id, module_name, module_type, capabilities, command_queue, registered_at, last_heartbeat, status, metadata`.
- Update the query in `backend/services/moduleHealthService.js` to include new metrics; keep computation in SQL where possible.

### Add workflow validation rules
- `workflowService.js` currently parses YAML to validate syntax. For schema validation, add a JSON Schema or custom validator and reject invalid documents with clear errors.

## Coding Standards & Quality

- Style: Prefer small, pure functions in services; keep route handlers thin.
- Errors: Catch and return machine‑readable messages; log full stack traces on the server.
- Backups: Keep the timestamped backups for configs/workflows (already implemented); prune old backups via a simple cron if needed.
- Commits: Use Conventional Commits (feat:, fix:, chore:, docs:, refactor:, test:).
- Lint/format (recommended):
  - Frontend uses CRA’s ESLint. Consider adding Prettier. Example scripts:
    - `"lint": "eslint src --max-warnings=0"`
    - `"format": "prettier -w \"src/**/*.{js,jsx,css}\""`
  - For backend, add ESLint with `eslint:recommended`.

## Testing Strategy (recommended)

- Backend: Jest + Supertest
  - Example: test `processManager.getAllStatus()` and each route in isolation.
- Frontend: React Testing Library
  - Example: render `Home` and mock `socket.io-client` to verify log rendering.
- Add `npm test` to CI for both packages.

## Deployment Notes

- Production build: `frontend` → `npm run build`. Serve the build via a static server or from Express.
- Express currently does not serve the React build. Two options:
  1) Add in `backend/server.js`:
     ```js
     const staticPath = path.join(__dirname, '../frontend/build');
     app.use(express.static(staticPath));
     app.get('*', (_, res) => res.sendFile(path.join(staticPath, 'index.html')));
     ```
  2) Deploy frontend and backend as separate services behind a reverse proxy (e.g., Nginx), with `/api` routed to the backend.
- Update CORS to the correct production origin.

## Realtime Logging Caveat

`logService` consumes queues and immediately `nack`s messages to avoid removing them. If it’s the only consumer, this can cause re‑delivery churn. Prefer running it alongside the normal consumers, or consider duplicating messages to a fan‑out exchange for non‑destructive observation.

## Security Considerations

- Don’t hardcode credentials; keep them in `.env` and never commit.
- Validate YAML and config edits; reject unsafe values. Consider RBAC for who can edit.
- Sanitize any path inputs and never allow directory traversal.
- Rate‑limit sensitive endpoints (process start/stop, config writes).

## Performance Tips

- Batch DB reads and add indices as needed; avoid N+1 in health queries.
- In the UI, debounce rapid actions; keep lists virtualized if they grow large.
- Throttle WebSocket log rendering; keep only the last N lines (already done: 100 lines on Home).

## Observability

- Centralize server logging and include request IDs.
- Emit structured logs for process lifecycle events.
- Add basic health/readiness endpoints (already: `/api/health`).

## Improvements Backlog (Actionable)

Short‑term
- Make API base URL configurable via `REACT_APP_API_BASE` and fallback to relative `/api`.
- Serve the React production build from Express (or document reverse‑proxy setup).
- Add `.env.example` to `backend` (use the template above).
- Add ESLint/Prettier to backend; wire scripts in both packages.
- Add Jest + Supertest tests for backend routes; RTL tests for critical pages.
- Improve CORS configuration (env‑driven allowlist).
- Add log filtering and search on the Logs page.

Medium‑term
- Authentication and authorization for config/workflow edits.
- Role‑based visibility of controls (e.g., process start/stop).
- Schema‑based workflow validation with clear error UI.
- Persist workflow revision history and diff view.
- Streaming logs pagination and download per process.
- Move hardcoded queue names in `logService` to env or config.

Long‑term
- Observability stack (OpenTelemetry/OTLP) and centralized logs/metrics.
- Bundle versioned API and type definitions (OpenAPI spec) for stronger frontend typing.
- Replace CRA with Vite or Next.js for better DX and SSR (if needed).

## Common Pitfalls

- Wrong paths in `.env` for Java programs → process start fails silently. Check server logs and `logs/*.log` files.
- Frontend can’t reach backend → verify backend on `:3001`, CORS origin, and API base URL in `api.js`.
- RabbitMQ logs not visible → check credentials/host; ensure there’s at least one other consumer if using the `nack` strategy.

## Maintenance Checklist

- Keep dependencies updated (both `/backend` and `/frontend`).
- Prune old log files and workflow/config backups.
- Add database indices as module health queries grow.
- Periodically validate `.env` settings across environments.

---
If you have questions or want to propose changes, open a PR with a brief design note under “What & Why” and link the files you’ll touch (both backend service and frontend page where applicable).
