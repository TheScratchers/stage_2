# 🚀 Search Engine - Stage 2: Service-Oriented Architecture (SOA)

> **Nota:** Este es un README provisional.

En esta fase, dejamos atrás el script de control monolítico del Stage 1 para evolucionar hacia una arquitectura distribuida basada en microservicios, preparando el sistema para su futuro despliegue en un clúster (Stage 3).

## 🛠️ ¿Qué se ha hecho? (La Migración)

Para cumplir con los requisitos del Stage 2, hemos reestructurado completamente el repositorio. Hemos extraído la lógica *core* (descargas, tokenización, índices y SQLite) que construimos en el Stage 1 y la hemos encapsulado en proyectos Maven independientes. 

**Importante:** Hemos añadido un `.gitignore` estricto. La carpeta `data/` (con los libros descargados, los datamarts y las bases de datos) **NO se sube a GitHub** para mantener el repositorio limpio y evitar conflictos o bloqueos por tamaño. Cada miembro generará sus propios datos en local.

### Estructura actual del repositorio

```text
stage_2/
├── ingestion-service/      # Lógica del Datalake (Downloader, Layout)
├── indexing-service/       # Extracción de metadatos, Tokenizer y almacenamiento en SQLite
├── search-service/         # ¡Nuevo! Estructura vacía para la API de búsqueda
├── benchmarks/             # Antiguos tests de JMH del Stage 1 aislados aquí
├── control-module/         # Módulo de control antiguo (pendiente de refactorizar/eliminar)
├── pom_referencia_stage1.xml # POM antiguo para consultar versiones de dependencias
└── README.md

```

*(Toda la estructura de paquetes de Java `com.thescratchers.searchengine` se ha mantenido intacta para no romper las referencias)*.

## 🎯 Objetivos a desarrollar en el Stage 2

Nuestra meta es transformar estas carpetas aisladas en microservicios concurrentes reales. A continuación, se presenta el resumen de la hoja de ruta técnica, seguido del desglose de cada fase:

| Fase / Objetivo | Componentes Clave | Descripción Breve |
| --- | --- | --- |
| **1. Configuración Multi-Módulo** | Maven, dependencias | Unificar la compilación creando un `pom.xml` padre y añadiendo Javalin y ActiveMQ. |
| **2. Arquitectura Orientada a Eventos** | ActiveMQ, Javalin | Desacoplar los servicios mediante un *message broker* (productor/consumidor). |
| **3. Diseño de Contratos (API)** | OpenAPI / Swagger | Definir los endpoints REST exactos (`GET /search`, `POST /ingest`) antes de programarlos. |
| **4. Concurrencia y Sincronización** | Hilos, Réplicas, SQLite | Implementar control de bloqueos para que múltiples *workers* no corrompan los índices. |
| **5. Benchmarking de Integración** | JMH, Métricas del sistema | Medir el *throughput* total, la latencia bajo carga y los cuellos de botella del pipeline. |

### Detalle de las fases de desarrollo

**1. Configuración Multi-Módulo (Maven)**
En lugar de compilar cada servicio a mano, crearemos un `pom.xml` padre en la raíz del repositorio. Este archivo gestionará las versiones de todas nuestras librerías compartidas. Integraremos **Javalin** para levantar los servidores web ligeros, **Gson/Jackson** para el manejo de JSON, y **ActiveMQ** para la comunicación asíncrona.

**2. Arquitectura Orientada a Eventos (Coreografía)**
Sustituiremos el antiguo `ControlLayer.java` por un sistema de mensajería (ActiveMQ) para evitar cuellos de botella:

* **Ingestion Service:** Expondrá el endpoint `POST /ingest/{id}`. Descargará el archivo, lo guardará en el datalake y publicará un evento `BookDownloaded` en la cola del broker.
* **Indexing Service:** Funcionará en segundo plano sin endpoints públicos. Estará suscrito a la cola del broker; al recibir el evento, leerá el datalake, extraerá los metadatos, actualizará SQLite y marcará el trabajo como terminado.
* **Search Service:** Expondrá `GET /search`. Solo realizará lecturas sobre la base de datos SQLite para servir los resultados JSON a los usuarios de forma casi instantánea.

**3. Diseño de Contratos (OpenAPI)**
Antes de escribir el código de los controladores en Javalin, diseñaremos el contrato de nuestra API usando la especificación OpenAPI (YAML/JSON). Documentaremos exactamente qué parámetros acepta el Front-End (búsqueda por *keyword*, filtros de *author*, *language*, *year*, y paginación) y qué códigos de estado HTTP devolvemos en caso de éxito o error.

**4. Concurrencia y Threading**
Este es el reto principal que exige el Stage 2. El sistema debe soportar múltiples peticiones a la vez. Si decidimos levantar varias réplicas del *Indexing Service* para que procesen libros en paralelo, debemos asegurar que no intenten escribir en la misma tabla de SQLite al mismo tiempo. Implementaremos mecanismos de control (bloqueos de archivo, transacciones ACID o reparto seguro de tareas) para evitar colisiones y corrupción de datos.

**5. Benchmarking de Integración**
Reutilizaremos nuestra experiencia con JMH, pero subiendo de nivel. Pasaremos de hacer *microbenchmarking* de funciones aisladas a medir el rendimiento de toda la arquitectura bajo estrés. Evaluaremos:

* **Throughput de ingesta:** Cuántos libros por segundo recorren todo el pipeline desde que se hace el `POST` hasta que están en SQLite.
* **Latencia de búsqueda:** El tiempo de respuesta del `GET /search` cuando hay cientos de consultas simultáneas.
* **Uso de recursos:** Monitorización de CPU y RAM para identificar cuál de los tres servicios se satura primero y necesita más réplicas en el futuro clúster del Stage 3.

---

**TheScratchers** - GCID ULPGC

