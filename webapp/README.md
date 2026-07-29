# Middleware Dashboard

A minimalist web dashboard for managing and monitoring the Middleware system.

## Features

- **System Overview**: Real-time status of all processes and modules
- **Control Panel**: Start/stop ThreatContextStore, ModuleRegistry, and WorkflowEngine
- **Real-Time Logs**: Live streaming of process logs and RabbitMQ messages
- **Workflow Management**: Create, read, update, and delete workflow YAML files
- **Configuration Editor**: Edit config.properties files for each program
- **Module Health**: Monitor status and heartbeats of user-defined modules
- **System Testing**: Run TCSTester to validate the complete pipeline
- **SDK Documentation** (`/sdk`): In-app reference for building user-defined modules - covers the
  embedded (`PluggableModule`) and standalone-process integration patterns, the `CoreSystemApi`/
  `Event<T>`/`ModuleHelper` reference, and worked examples. See `/sdk`, `/sdk/core-api`, and
  `/sdk/patterns`.

## Architecture

### Backend (Node.js/Express)
- REST API for process management, config/workflow CRUD
- WebSocket (Socket.IO) for real-time log streaming
- PostgreSQL client for module health queries
- RabbitMQ consumer for monitoring message queues
- Located in: `webapp/backend/`

### Frontend (React)
- Modern, minimalist UI with dark blue/black/white theme
- Real-time updates via WebSocket
- Responsive design
- Located in: `webapp/frontend/`

## Prerequisites

- **Node.js** 16+ and npm
- **PostgreSQL** database (configured in `.env`)
- **RabbitMQ** server (configured in `.env`)
- **Java 17+** for running Middleware programs

## Installation

### Option A (Recommended): From repository root

After cloning the repository:

```bash
cd Middleware
npm install
```

This will:
- Install backend dependencies (`webapp/backend`)
- Install frontend dependencies (`webapp/frontend`)
- Create `webapp/backend/.env` from `.env.example` if it does not exist

You can then start everything with:

```bash
npm run start
```

This runs the backend and frontend in parallel.

### Option B: Manual per-app install

If you prefer to manage each app separately:

#### 1. Install Backend Dependencies

```bash
cd webapp/backend
npm install
```

#### 2. Install Frontend Dependencies

```bash
cd webapp/frontend
npm install
```

### 3. Configure Environment

Edit `webapp/backend/.env` to match your setup:

```bash
cd webapp/backend
# Edit .env file with your database/RabbitMQ credentials and paths
```

## Running the Dashboard

### Option 1: Development Mode (Recommended)

**Terminal 1 - Start Backend:**
```bash
cd webapp/backend
npm run dev
# Backend runs on http://localhost:3001
```

**Terminal 2 - Start Frontend:**
```bash
cd webapp/frontend
npm start
# Frontend opens at http://localhost:3000
```

### Option 2: Production Mode

**Build frontend:**
```bash
cd webapp/frontend
npm run build
```

**Start backend:**
```bash
cd webapp/backend
npm start
# Serves both API and static frontend
```

## Usage

1. **Open Dashboard**: Navigate to http://localhost:3000
2. **Start Programs**: Go to Control Panel and start the programs you need
3. **View Logs**: Check the Logs page for real-time output
4. **Monitor Modules**: View module health on the Modules page
5. **Test System**: Use the Testing page to run TCSTester

## API Endpoints

### Processes
- `GET /api/processes/status` - Get status of all processes
- `POST /api/processes/:name/start` - Start a process
- `POST /api/processes/:name/stop` - Stop a process
- `POST /api/processes/test` - Run TCSTester

### Configuration
- `GET /api/config/:program` - Get config file content
- `PUT /api/config/:program` - Update config file

### Workflows
- `GET /api/workflows` - List all workflows
- `GET /api/workflows/:category/:name` - Get specific workflow
- `POST /api/workflows/:category` - Create workflow
- `PUT /api/workflows/:category/:name` - Update workflow
- `DELETE /api/workflows/:category/:name` - Delete workflow

### Modules
- `GET /api/modules/health` - Get module health status

## WebSocket Events

### From Server to Client
- `process-log` - Process stdout/stderr output
- `rabbitmq-log` - RabbitMQ queue messages

## Directory Structure

```
webapp/
├── backend/
│   ├── server.js              # Main Express server
│   ├── services/
│   │   ├── processManager.js  # Process lifecycle management
│   │   ├── logService.js      # WebSocket log streaming
│   │   ├── configService.js   # Config file operations
│   │   ├── workflowService.js # Workflow CRUD
│   │   └── moduleHealthService.js # Database queries
│   ├── package.json
│   └── .env
├── frontend/
│   ├── src/
│   │   ├── App.js             # Main React app with routing
│   │   ├── App.css            # Minimalist dark theme
│   │   ├── components/
│   │   │   └── Layout.js      # Sidebar layout
│   │   ├── pages/
│   │   │   ├── Home.js        # System overview
│   │   │   ├── ControlPanel.js # Process management
│   │   │   ├── Logs.js        # Real-time logs
│   │   │   ├── Workflows.js   # Workflow editor
│   │   │   ├── Configuration.js # Config editor
│   │   │   ├── Modules.js     # Module health
│   │   │   ├── Testing.js     # Test runner
│   │   │   ├── SdkDocs.js     # SDK docs shell (sub-nav + routing for the three below)
│   │   │   ├── SdkOverview.js # SDK intro, getting-started walkthrough
│   │   │   ├── SdkCoreApi.js  # CoreSystemApi/PluggableModule/Event<T>/ModuleHelper reference
│   │   │   └── SdkPatterns.js # Reactive/proactive/correlation/standalone-process patterns
│   │   └── services/
│   │       └── api.js         # API client
│   └── package.json
├── logs/                      # Process log files
└── README.md
```

## Troubleshooting

### Backend won't start
- Check `.env` configuration
- Ensure PostgreSQL and RabbitMQ are running and accessible
- Check that all paths in `.env` are correct

### Frontend can't connect to backend
- Ensure backend is running on port 3001
- Check CORS settings in `server.js`
- Verify API_BASE in `frontend/src/services/api.js`

### Processes won't start
- Ensure Java programs are compiled (`target/classes` directories exist)
- Check JAR files are in each program's `lib/` directory
- Verify paths in backend `.env` file

### No RabbitMQ logs appearing
- Check RabbitMQ credentials in `.env`
- Ensure RabbitMQ queues exist and are accessible
- Check backend console for connection errors

## Design Philosophy

- **Minimalist**: Clean, distraction-free interface
- **Dark Theme**: Easy on the eyes with dark blue/black/white palette
- **Real-Time**: WebSocket streaming for instant feedback
- **Robust**: Proper error handling and reconnection logic
- **Efficient**: Optimized API calls and state management

## Future Enhancements

- User authentication
- Advanced log filtering and search
- Workflow execution history
- Module performance metrics
- Alert notifications
- Configuration validation

## License

Same as parent Middleware project.
