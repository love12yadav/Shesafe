# 🚨 SheSafe: SOS Safety Alert System (Real-Time)

A real-time SOS alert and live location tracking system built with Spring Boot, Kafka, WebSockets (STOMP), React (Vite) and JWT authentication, now with an **AI-powered Area Risk module** (RAG + Gemini + pgvector) for Delhi.

Users can add trusted contacts, send SOS alerts, and trusted contacts receive instant notifications and live location updates. Users can also look up how an area ranks by reported crime, with a short AI explanation and cited sources.

---

## 🧠 Features

### 👤 Authentication
- JWT-based login and registration
- Protected routes in the frontend
- User identity extracted from the token (no hardcoded IDs)

### 🤝 Trusted Contacts
- Add other registered users as trusted contacts
- SOS alerts are sent only to trusted contacts

### 🚨 SOS Alerts
- 3 SOS levels: Level 1, Level 2, Level 3
- Alerts are persisted and streamed in real time
- Kafka used for reliable event delivery
- Email notifications to contacts (SMTP)

### 📍 Live Location Tracking
- Sender's location updated every 10 seconds during an active SOS
- Trusted contacts can see the live location on a map

### 🔔 Real-Time Notifications
- WebSocket (STOMP + SockJS)
- Each user subscribes to `/topic/alerts/{userId}`
- No duplicate or wrong subscriptions

### 🤖 AI Area Risk (new)
- Rates Delhi police areas as **HIGH**, **MODERATE** or **SAFE** based on reported crime density (crimes per km²)
- **RAG explanation:** retrieves relevant text from the crime reports and asks Gemini for a short explanation, safety tips and source citations
- **SQL decides the level, the LLM only explains**, so the model can never invent a rating
- Per-area cache, request timeout, and a no-AI fallback if Gemini is slow or rate limited
- Map-ready data: every area returns latitude and longitude

> ⚠️ Ratings are estimates based on reported crime data. They are not a guarantee of safety, and wording in the app should say "higher reported crime in this area", never "dangerous".

---

## 🏗️ Tech Stack

### Backend
- Spring Boot 4, Java 21
- Spring WebSocket (STOMP)
- Apache Kafka
- Spring Security + JWT
- JPA / Hibernate (MySQL for application data)
- **Spring AI 2.x** with **Google Gemini** (chat and embeddings)
- **PostgreSQL + pgvector** (vector store)

### Frontend
- React (Vite)
- Axios
- SockJS + @stomp/stompjs
- Tailwind CSS
- React Router

---

## 🧩 Architecture Overview

### SOS flow

```
Frontend (React)
   |
   |  REST API (JWT Auth)
   v
Spring Boot Backend
   |
   |  Kafka Producer
   v
Kafka Topic (alerts-topic)
   |
   |  Kafka Consumer
   v
WebSocket (STOMP) / Email
   |
   v
Trusted Contacts (Live Notifications)
```

### AI Area Risk flow

```
Startup (runs once, idempotent)
  report2.md ──► AreaCrimeLoaderService ──► MySQL: area_crime (levels, lat/lng)
  report1.pdf ─┐
  area summaries ─► RagIngestionService ──► chunk ─► Gemini embeddings ─► pgvector

Request: GET /area-risk/explain/{name}
  1. Level and counts  ◄── SQL (area_crime)
  2. Relevant chunks   ◄── pgvector similarity search (area-filtered + general)
  3. Gemini writes explanation + tips using ONLY those chunks
  4. Response: level, explanation, tips, sources (fallback if Gemini fails)
```

---

## 🔑 WebSocket Flow (Important)

1. User logs in and the JWT is stored in localStorage
2. `userId` is decoded from the JWT
3. Frontend subscribes to `/topic/alerts/{userId}`
4. Backend sends alerts only to trusted contacts

### Example WebSocket subscription

```js
client.subscribe(`/topic/alerts/${userId}`, (msg) => {
  const alert = JSON.parse(msg.body);
  console.log("Received alert:", alert);
});
```

---

## 🤖 AI Area Risk: Details

### Data sources
Place these in `src/main/resources/data/`:

| File | Used for |
|---|---|
| `report2.md` | Structured area data (crime counts, coordinates, area size). Loaded into the `area_crime` table, which decides the risk level |
| `report1.pdf` | Report text. Chunked, embedded and used by RAG for explanations and citations |

### How levels are computed
Areas are ranked by **crimes per km²**. The top 25% are HIGH, the bottom 25% are SAFE, and the rest are MODERATE. The percentiles are configurable:

```properties
area.risk.threshold.high-percentile=75
area.risk.threshold.safe-percentile=25
```

### API (all endpoints require a JWT)

| Method | Endpoint | Description |
|---|---|---|
| GET | `/area-risk/areas` | All areas with level, totals, latitude and longitude |
| GET | `/area-risk/areas/search?q=` | Search areas by name |
| GET | `/area-risk/areas/{name}` | One area's numbers (no AI) |
| GET | `/area-risk/explain/{name}` | Level, AI explanation, tips and sources |

Example response of `/area-risk/explain/{name}`:

```json
{
  "area": "AREA NAME",
  "level": "HIGH",
  "crimeCount": 0,
  "explanation": "...",
  "tips": ["...", "..."],
  "sources": ["report1.pdf, page 12"],
  "fallback": false
}
```

`fallback: true` means the AI explanation was unavailable, so only the SQL-based level and counts are returned.

### Frontend
The area search page and risk map (colored circles per area, click for the AI explanation) are planned.

---

## 🗂️ Project Structure

### Backend

```
src/main/java/com/example/demo
 ├── controller
 ├── model
 ├── repository
 ├── security
 ├── kafka
 │    ├── AlertProducer
 │    ├── WebSocketConsumer
 │    └── EmailConsumer
 ├── websocket
 └── arearisk
      ├── config        (PgVectorConfig: pgvector datasource and vector store)
      ├── controller    (AreaRiskController)
      ├── model         (AreaCrime)
      ├── repository    (AreaCrimeRepository)
      ├── service       (AreaCrimeLoaderService, RagIngestionService,
      │                  AreaRiskService, AreaRiskResponse)
      └── setup         (PgVectorSetupCheck, AreaRiskStartupRunner)

src/main/resources
 └── data
      ├── report1.pdf
      └── report2.md
```

### Frontend

```
src/
 ├── components/
 ├── pages/
 ├── websocket/
 │    └── socket.js
 ├── api/
 └── App.jsx
```

---

## ▶️ Running the Project

### Prerequisites
- Java 21 and Maven
- MySQL (main application database)
- Kafka and Zookeeper running
- Docker (for pgvector)
- A Google Gemini API key from Google AI Studio

### 1. Start PostgreSQL with pgvector

```bash
docker run -d --name sosdb -p 5432:5432 \
  -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=sos \
  pgvector/pgvector:pg16
```

### 2. Set environment variables and properties

```bash
# Linux / macOS
export GEMINI_API_KEY=your_key_here

# Windows PowerShell
$env:GEMINI_API_KEY="your_key_here"
```

Never commit real keys or passwords. Add these to `application.properties` (placeholders shown):

```properties
# Main DB (MySQL)
spring.datasource.url=jdbc:mysql://localhost:3306/your_db
spring.datasource.username=...
spring.datasource.password=...

# pgvector DB (PostgreSQL)
pgvector.datasource.url=jdbc:postgresql://localhost:5432/sos
pgvector.datasource.username=postgres
pgvector.datasource.password=pass

# Gemini
spring.ai.google.genai.api-key=${GEMINI_API_KEY}
spring.ai.google.genai.chat.options.model=gemini-2.5-flash
spring.ai.google.genai.embedding.api-key=${GEMINI_API_KEY}
spring.ai.google.genai.embedding.text.options.model=gemini-embedding-001
spring.ai.google.genai.embedding.text.options.dimensions=768

# Must equal the embedding dimensions above
spring.ai.vectorstore.pgvector.dimensions=768
area.risk.gemini.timeout-seconds=15
```

Property and model names can change between Spring AI versions, so check the Spring AI docs and Google's current model list.

### 3. Backend

```bash
mvn clean install
mvn spring-boot:run
```

Make sure Kafka and Zookeeper are running.

On the **first run**, the app automatically:
1. Checks that pgvector works and prints the vector size
2. Loads `report2.md` into the `area_crime` table and assigns levels
3. Chunks and embeds `report1.pdf` and the area summaries into pgvector

Ingestion is idempotent, so restarting skips what is already loaded. Embedding calls use your Gemini quota, so avoid wiping the vector store without reason.

### 4. Frontend

```bash
npm install
npm run dev
```

---

## ✅ Verifying the AI Module

```sql
-- in PostgreSQL: chunks per source
SELECT metadata->>'source', COUNT(*) FROM vector_store GROUP BY 1;
```

- The `excel` count should equal the number of areas.
- `report1.pdf` rows should have `page_number` in their metadata.
- `GET /area-risk/areas` should return a mix of HIGH, MODERATE and SAFE.
- `GET /area-risk/explain/{name}` should return an explanation with sources and `fallback: false`.

---

## ⚠️ Limitations

- Crime data is aggregated, so it cannot show time of day or specific streets.
- Reported crime is skewed by reporting rates, so levels are estimates.
- Small dense areas can rank higher on density than on raw counts.
- Gemini's free tier has rate limits. The app falls back to SQL-only answers when it is rate limited.

---

## 📜 License

Add your license here.
