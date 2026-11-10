# Environment setup report

Date: 2026-11-02

| Tool | Result |
|---|---|
| OS | Windows 11 Home Single Language, 64-bit |
| Node.js | 24.16.0 |
| npm | 11.13.0 |
| Git | 2.54.0.windows.1 |
| Docker Compose CLI | v5.3.0 |
| Docker Engine | 29.6.1, reachable and used successfully |
| kubectl | Client installed, version command succeeded |
| Java/JDK | Temurin 21.0.13 LTS installed and verified |
| Maven | Apache Maven 3.9.9 installed under `C:\Tools\apache-maven-3.9.9` and verified |
| Minikube | v1.39.0 installed; Docker-driver cluster started and verified |
| kind | Not installed |

Observed resources: approximately 15.4 GiB total memory with approximately 3.2 GiB free at inspection; E: drive capacity was not available from the resource query.

The current Node/React project was built and verified. Docker Compose infrastructure containers (PostgreSQL, MongoDB, RabbitMQ, Prometheus, and Grafana) were started and health-checked. No administrator-level security-control bypass was attempted.
