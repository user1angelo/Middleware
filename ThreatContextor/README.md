# Middleware with PostgreSQL

## Why PostgreSQL? 

- **Open-source & free:** No licensing costs, unlike some commercial databases.  
- **Reliability & maturity:** Proven in production at large-scale systems.  
- **JSON/UUID support:** Perfect for storing flexible alert data with unique identifiers.  
- **Strong SQL & indexing:** Powerful queries, indexing, and transactional guarantees.  
- **Good Java integration:** JDBC support makes it easy to connect from Java apps.  

**Why not other database?**  
- Some databases (e.g., MySQL) have weaker JSON handling.  
- NoSQL options (MongoDB, Couchbase) lack strong ACID guarantees for transactional alerts.  
- Lightweight DBs (SQLite) may struggle with concurrent writes and scaling.


---

## Requirements
- **Java 17+** installed  
- **PostgreSQL 17** installed  
- **PostgreSQL JDBC Driver** (`postgresql-42.7.7.jar`)  

Download JDBC driver:  
👉 https://jdbc.postgresql.org/download.html  

Place it inside the `lib/` folder of this project.  

---

## Step 1: Install PostgreSQL
1. Download installer:  
   👉 [EnterpriseDB PostgreSQL Installer](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads)

2. During installation:  
   - Choose **PostgreSQL Server**  
   - Port: `5432` (default)  
   - Superuser: `postgres`  
   - Password: (choose your password, e.g. `postgres`)  

3. Once installed, open **pgAdmin 4** or `psql`.  

---

## Step 2: Setup Database and Schema
1. Create the database and user:

```sql
CREATE DATABASE alertsdb;
CREATE USER alerts_user WITH PASSWORD 'alertspass';
GRANT ALL PRIVILEGES ON DATABASE alertsdb TO alerts_user;
```

2. Run the schema file (`schema.sql`) inside `alertsdb`:

```sql
\c alertsdb
\i schema.sql
```

---

## Step 3: Compile Middleware
```sh
javac -cp "lib/*" -d out src/main/java/com/yourorg/middleware/*.java
```

---

## Step 4: Run Sender (Insert Alerts)
```sh
java -cp "out;lib/*" com.yourorg.middleware.Sender
```

This will insert all alerts from `messages/` folder into PostgreSQL.

---

## Step 5: Run QueryDemo (Export Alerts)
```sh
java -cp "out;lib/*" com.yourorg.middleware.QueryDemo high
```

This will export all alerts with severity = `high` into the `output/` folder as JSON files.

---
