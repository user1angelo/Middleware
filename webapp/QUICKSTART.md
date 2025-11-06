# Middleware Dashboard - Quick Start

## 🚀 Get Started in 2 Minutes

### Step 1: Install Dependencies (First Time Only)

```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/webapp

# Install backend
cd backend && npm install && cd ..

# Install frontend  
cd frontend && npm install && cd ..
```

### Step 2: Configure (Optional)

Edit `backend/.env` if your PostgreSQL/RabbitMQ settings differ from defaults:

```bash
nano backend/.env
```

### Step 3: Start Dashboard

**Option A - Using the convenience script:**
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/webapp
./start-dashboard.sh
```

**Option B - Manual (recommended for development):**

Terminal 1 - Backend:
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/webapp/backend
npm start
```

Terminal 2 - Frontend:
```bash
cd /home/keyanluwi/Documents/GitHub/Middleware/webapp/frontend
npm start
```

### Step 4: Access Dashboard

Open your browser to: **http://localhost:3000**

## 📋 What You Can Do

1. **Home** - See system overview and module status
2. **Control Panel** - Start/stop Java programs with one click
3. **Logs** - Watch real-time process and RabbitMQ logs
4. **Workflows** - Create/edit/delete workflow YAML files
5. **Configuration** - Edit config.properties for each program
6. **Modules** - Monitor health of user-defined modules
7. **Testing** - Run TCSTester to validate the system

## 🎨 Design Features

- Minimalist dark theme (black/dark blue/white)
- Real-time WebSocket updates
- No clutter, just functionality
- Responsive and fast

## ⚡ Quick Actions

### Start a Program
1. Go to Control Panel
2. Click "Start" next to ThreatContextStore/ModuleRegistry/WorkflowEngine
3. Watch logs appear in real-time on Logs page

### Run a Test
1. Go to Testing page
2. Click "Run Test"
3. Watch as 10 alerts are sent through the system
4. Check Logs to see processing

### Edit a Workflow
1. Go to Workflows page
2. Select a workflow from the list
3. Click "Edit"
4. Make changes
5. Click "Save"

### Change Configuration
1. Go to Configuration page
2. Select a program tab
3. Edit the config
4. Click "Save Changes"

## 🔧 Troubleshooting

**Backend won't start?**
- Check that PostgreSQL (192.168.1.8:5432) is accessible
- Check that RabbitMQ (192.168.1.8:5672) is accessible
- Review `backend/.env` settings

**Can't start Java programs?**
- Ensure programs are compiled (run `javac` in each directory)
- Check that `lib/` folders contain all JARs
- Verify paths in `backend/.env`

**No logs showing?**
- Make sure programs are running (check Control Panel)
- Check backend console for errors
- Refresh the page

## 📚 Full Documentation

See `README.md` for complete documentation.

## 🎯 Next Steps

1. Start the dashboard
2. Start ThreatContextStore from Control Panel
3. Run a test from Testing page
4. Watch the magic happen in Logs!

---

**Enjoy your minimalist monitoring experience! 🚀**
