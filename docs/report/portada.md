# Stage 2: Service-Oriented Search Engine (SOA)

**Subject:** Big Data  
**Degree:** Grado en Ciencia e Ingeniería de Datos (GCID)  
**Institution:** Escuela de Ingeniería Informática, Universidad de Las Palmas de Gran Canaria (ULPGC)  
**Academic Year:** 2026/2027  
**Team:** TheScratchers  
**Repository:** [https://github.com/TheScratchers/stage_2](https://github.com/TheScratchers/stage_2)  

---

### Authors and Responsibilities

| Student | ID / Contact | Module Responsibilities | Report Contributions |
|---|---|---|---|
| **Pablo Martínez Suárez** | `pablomrtinezzz@gmail.com` | Ingestion Service, Embedded ActiveMQ Broker, Execution Scripts, Ingestion Benchmarks | Cover, Introduction, Architecture, Ingestion Implementation, Ingestion Benchmarks, Final PDF Compilation |
| **Amado Artiles Rodríguez** | `amadoart22@gmail.com` | Indexing Service, Broker Consumers, Concurrency & Idempotent Transactions, JMH Benchmarks | Indexing Implementation, Indexing Concurrency, JMH Microbenchmarks |
| **Aimar Alejandro Santana** | `aimarsaal@gmail.com` | Search Service, OpenAPI 3.0 Specification, API Validation, Integration Benchmarks | API & Contracts, Search Implementation, Integration Benchmarks, Conclusions & Stage 3 |

---

### Reference Execution Environment

All official benchmark figures and system measurements reported in this document were executed under strictly controlled conditions on the agreed team reference machine:

- **Processor (CPU):** Intel Core i7-13700KF (16 physical cores: 8 Performance-cores @ 3.40–5.40 GHz + 8 Efficient-cores @ 2.50–4.20 GHz, 24 logical processors).
- **RAM:** 32 GB (2 × 16 GB) Kingston FURY Beast DDR5 5200 MHz CL40.
- **Motherboard:** MSI B760 GAMING PLUS WIFI.
- **Storage:** 1 TB NVMe M.2 SSD (Kioxia Exceria G2, PCIe 3.0 x4).
- **Operating System:** Windows 11 Pro 64-bit (build 10.0, amd64).
- **Java Runtime Environment (JVM):** Eclipse Adoptium Temurin 21.0.12.1+1 LTS (OpenJDK 64-Bit Server VM).
- **Build System:** Apache Maven 3.9.16 (language level `<release>17</release>`).
- **Baseline Commit:** `04132ab` (official measurement freeze).
