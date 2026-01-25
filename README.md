🚨 SOS Safety Alert System (Real-Time)

A real-time SOS alert and live location tracking system built with Spring Boot, Kafka, WebSockets (STOMP), React (Vite) and JWT authentication.
Users can add trusted contacts, send SOS alerts, and trusted contacts receive instant notifications + live location updates.

🧠 Features
👤 Authentication

JWT-based login & registration

Protected routes in frontend

User identity extracted from token (no hardcoded IDs)

🤝 Trusted Contacts

Add other registered users as trusted contacts

SOS alerts are sent only to trusted contacts

🚨 SOS Alerts

3 SOS levels: Level 1, Level 2, Level 3

Alerts are persisted and streamed in real time

Kafka used for reliable event delivery

📍 Live Location Tracking

Sender’s location updated every 10 seconds during active SOS

Trusted contacts can see live location on map

🔔 Real-Time Notifications

WebSocket (STOMP + SockJS)

Each user subscribes to:

/topic/alerts/{userId}


No duplicate or wrong subscriptions

🏗️ Tech Stack
Backend

Spring Boot

Spring WebSocket (STOMP)

Apache Kafka

Spring Security + JWT

JPA / Hibernate

MySQL / PostgreSQL

Frontend

React (Vite)

Axios

SockJS + @stomp/stompjs

Tailwind CSS

React Router

🧩 Architecture Overview
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
WebSocket (STOMP)
   |
   v
Trusted Contacts (Live Notifications)

🔑 WebSocket Flow (Important)

User logs in → JWT stored in localStorage

userId is decoded from JWT

Frontend subscribes to:

/topic/alerts/{userId}


Backend sends alerts only to trusted contacts

🧪 Example WebSocket Subscription
client.subscribe(`/topic/alerts/${userId}`, (msg) => {
  const alert = JSON.parse(msg.body);
  console.log("Received alert:", alert);
});

🗂️ Project Structure
Backend
src/main/java
 ├── controller
 ├── model
 ├── repository
 ├── security
 ├── kafka
 │    ├── AlertProducer
 │    └── WebSocketConsumer
 └── websocket

Frontend
src/
 ├── components/
 ├── pages/
 ├── websocket/
 │    └── socket.js
 ├── api/
 └── App.jsx

▶️ Running the Project
Backend
mvn clean install
mvn spring-boot:run


Make sure Kafka & Zookeeper are running.

Frontend
npm install
npm run dev
