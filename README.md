# BaaPP (OviApp) — App Móvil Offline-First & Backend API

> **"Tu rebaño en orden, con o sin señal."**  
> **Proyecto Integrador — AgroTech Challenge (TECOWEEK Winter Edition 2026)**  
> **Universidad Autónoma de Guadalajara (UAG)**

---

## Descripción del Proyecto

**BaaPP (OviApp)** es una plataforma mexicana *mobile-first* y *offline-first* de gestión zootécnica, trazabilidad genealógica y control financiero para ranchos ovinos (de 10 a 1,000+ cabezas), diseñada para sustituir software extranjero costoso y complejo (*OvisWebs*) con una experiencia adaptada al campo mexicano.

El sistema opera bajo una arquitectura distribuida en dos repositorios que comparten una **única fuente de verdad (Single Backend & Database)**:
* **`BaaPP-app` (este repositorio):** Monorepo Kotlin/Gradle que aloja la **App Móvil Kotlin Multiplatform (KMP)** para captura en corral sin señal y el **Backend API en Kotlin + Spring Boot 3.4** bajo **Onion Architecture**, compartiendo fórmulas zootécnicas deterministas y reglas de validación.
* **[`BaaPP_webapp`](https://github.com/Baa-PP/BaaPP_webapp):** Portal Web de escritorio en **React 19 + TypeScript** para auditoría multi-rancho de la Asociación, visualización de la Sábana de 24 columnas, árboles genealógicos y simulaciones financieras.

Desarrollado en vinculación con:
* **AGLOT** (Asociación Ganadera Local de Ovinocultores de Tlaquepaque)
* **PELIFOLK** (Asociación Mexicana de Criadores de Ovinos Pelifolk de Registro, A.C.)
* **SADER Jalisco** (Secretaría de Agricultura y Desarrollo Rural del Estado de Jalisco)

---

## Módulos y Reglas de Negocio (Especificación Sesión 2)

1. **Módulo 1 — Inventario General y Ficha Individual (Reseña):**
   * Clasificación relacional en **Hembras (Vientres)**, **Sementales** (con medición de circunferencia testicular en cm) y **Crías** (lactancia, recría o engorda).
   * Identificadores desacoplados sobre llave primaria inmutable `UUIDv7`: **Folio/Arete interno** de rancho (`UNIQUE (ranch_id, folio_interno)`), microchip oficial **SINIIGA** (10–15 dígitos con prefijo `484...`) y **Folio de Registro UNO** (ej. `268309MM-RP`).
   * **Navegación bidireccional total** (Madre ↔ Crías ↔ Semental), árbol genealógico de **4 generaciones** (distinguiendo **Criador** vs. **Propietario actual**), código visual por sexo (**Rosa** para hembras, **Azul** para machos siempre acompañado de texto/iconografía) y registro fotográfico de **Campeonatos / Exposiciones**.
2. **Módulo 2 — Registro de Nacimientos, Regla Anti-Fraude (30 Días) y Destete (75 Días):**
   * **Bloqueo Anti-Fraude:** Captura por evento de parto con **fotografía obligatoria** de las crías con la madre y ventana máxima de **30 días (cierre mensual)**. Una vez guardado o cerrado el mes, el registro es **inmutable** para el productor (`OWNER` / `WORKER_BRANCH`).
   * **Correcciones Auditadas:** Solo los roles `ASSOCIATION_SUPERUSER` o `VET_TECHNICIAN` pueden corregir errores de captura ("error de dedo"), generando un registro inmutable en `birth_audit_logs` con estado previo/nuevo en `JSONB`, motivo y llave de evidencia fotográfica en S3.
   * **Ciclo de Lactancia y Alerta a los 75 Días:** Pesajes en Nacimiento, Destete (60–75d), 120d, 150d y Evaluación con cálculo automático de **Ganancia Diaria de Peso (GDP)** y edad dinámica en meses/días. Al cumplirse `Fecha de Nacimiento + 75 días` (configurable), el sistema emite alerta push (FCM); al confirmar el destete, la madre pasa automáticamente de *Lactancia* a *Mantenimiento (Vacía)* y cambia su fórmula alimenticia.
3. **Módulo 3 — Cartilla Sanitaria, Corrales y Cuarentena:**
   * Historial por animal o lote en 4 rubros: **Vacunación**, **Vitaminación**, **Aplicación de Selenio** y **Desparasitación**.
   * Ubicación por corral y transferencia al área de **Cuarentena / Enfermería** del propio rancho (estado *"En tratamiento"* con diagnóstico y medicación).
4. **Módulo 4 — Manejo Alimenticio (5 Etapas) y Proyección Productiva:**
   * Formulación para **Lactancia** (~18% PC), **Crecimiento** (*creep feeding*), **Desarrollo** (recría), **Engorda** y **Mantenimiento**, con catálogo de granos/forrajes con `% de proteína` constante precargado y precios regionales (`$/kg TCO`) editables por productor.
   * Implementación exacta en `BigDecimal` del modelo determinista del **Mtro. Alejandro Meléndez** (ciclo productivo de 275 días):
     * **% Preñez:** `Hembras Preñadas / Hembras Expuestas`
     * **% Partos:** `Hembras Paridas / Hembras Preñadas` (y global sobre expuestas)
     * **Prolificidad:** `Crías Nacidas / Hembras Paridas`
     * **Intervalo entre Ciclos:** `Empadre + Gestación + Lactancia + Descanso Post-Destete`
     * **Días de Engorda:** `(Peso Final - Peso Destete) / GDP`
     * **Costeo Alimenticio (MS a TCO):** `(kg MS por día / % MS dieta) * Precio por kg TCO`
5. **Módulo 5 — Mortalidad Histórica y Detección de Vientres Improductivos:**
   * **Prohibición de borrado físico (`DELETE`):** Las bajas transicionan a `status = 'DECEASED'` conservando fecha, causa y etapa para estadísticas de mortalidad y conciliación de chips SINIIGA.
   * **Evaluación Materna Automática:** Identifica vientres improductivos (infertilidad o mortalidad consecutiva de camadas) para recomendar su envío a rastro/desecho.

---

## Stack Tecnológico y Arquitectura (`BaaPP-app`)

| Capa | Tecnología Seleccionada | Librerías y Módulos Clave | Justificación Técnica |
| :--- | :--- | :--- | :--- |
| **App Móvil (Android + Futuro iOS)** | **Kotlin Multiplatform (KMP)** | Jetpack Compose (Material 3), SQLDelight, Ktor Client, Kotlinx Serialization, Koin, Android WorkManager, Coil | Rendimiento nativo y bajo consumo de RAM en celulares Android económicos de corral; 100% de lógica de dominio, base de datos offline y motor de sincronización compartidos para futura compilación iOS. |
| **Backend API (Onion Architecture)** | **Kotlin + Spring Boot 3.4 (Java 21)** | Spring Web, Spring Data JPA (Hibernate), Spring Security 6 (JWT), FastExcel (`org.dhatim`), AWS SDK for Kotlin v2, ArchUnit | Ecosistema Kotlin unificado con KMP; cumplimiento estricto de POO y aislamiento de dominio verificado con ArchUnit; generación streaming `.xlsx` de la Sábana de 24 columnas; `@Scheduled` cron jobs nativos. |
| **Base de Datos Relacional** | **PostgreSQL 16 (Amazon RDS)** | Flyway Migrations, `UUIDv7`, `WITH RECURSIVE` CTEs, `JSONB` | Cero costo de licencias; consultas recursivas en milisegundos para pedigrí de 4 generaciones; unicidad compuesta por rancho; bitácoras de auditoría inmutables en `JSONB`. |
| **Infraestructura Cloud (Zero-Docker)** | **Amazon Web Services (AWS)** | Elastic Beanstalk (Corretto 21 Single Instance `t3.micro`), RDS (`db.t4g.micro`), Amazon S3, Firebase Cloud Messaging (FCM) | Despliegue directo de `.jar` vía GitHub Actions sin sobrecarga de contenedores; **$0.00/mes** durante la fase académica operando dentro del AWS Free Tier. |

---

## Protocolo de Sincronización Offline-First

1. **Captura Local en Corral:** La app KMP inserta registros en **SQLDelight** usando identificadores `UUIDv7` generados en el cliente, comprime las fotos de evidencia localmente (~150–200 KB WebP/JPEG) y encola la mutación en `pending_sync_events` (`sync_status = 'PENDING'`).
2. **Carga Directa de Evidencia a S3:** Al detectar red (`WorkManager` con `NetworkType.CONNECTED`), `OfflineSyncEngine` solicita Pre-Signed PUT URLs al backend, sube las fotos directamente al bucket privado `agrotech-oviapp-media` en Amazon S3 y adjunta el `s3Key` al evento.
3. **Envío por Lotes Idempotente:** El cliente envía el lote ordenado de mutaciones a `POST /api/v1/sync/batch`.
4. **Deduplicación y Validación en Servidor:** Spring Boot verifica cada `mutation_id` contra `processed_sync_mutations` dentro de una transacción (garantizando idempotencia ante reintentos), valida las reglas de `BirthAntiFraudPolicy` y persiste los cambios.

---

## Estructura del Repositorio

```text
BaaPP-app/
├── shared/                                  # Módulo Kotlin Multiplatform compartido (Android & iOS)
│   └── src/commonMain/kotlin/com/agrotech/oviapp/
│       ├── domain/                          # Modelos (Animal, BirthEvent, WeightRecord) y calculadoras
│       ├── data/local/                      # Esquemas SQLDelight (.sq) y queries reactivas (Flow)
│       ├── data/remote/                     # Cliente Ktor HTTP, refresh JWT y subida S3
│       ├── sync/                            # OfflineSyncEngine (cola de salida idempotente)
│       └── presentation/                    # ViewModels compartidos (StateFlow<UiState>)
├── androidApp/                              # Aplicación nativa Android (Jetpack Compose)
│   └── src/main/kotlin/com/agrotech/oviapp/android/
│       ├── ui/                              # Pantallas Compose (botones >=56dp, alto contraste, Rosa/Azul)
│       └── worker/                          # Sincronización en segundo plano con WorkManager
└── backend/                                 # API Central en Kotlin + Spring Boot 3.4 (Onion Architecture)
    └── src/main/
        ├── kotlin/com/agrotech/oviapp/
        │   ├── domain/                      # Núcleo puro (sin dependencias Spring/JPA)
        │   │   ├── model/                   # Dimensiones ortogonales (sex, lifeStage, reproductiveStatus, status)
        │   │   ├── service/                 # BirthAntiFraudPolicy, ZootechnicalCalculator, MaternalEvaluationService
        │   │   └── port/out/                # Interfaces de salida (Repositories, Excel, S3, Notifications)
        │   ├── application/usecase/         # Casos de uso (@Transactional: RegisterBirth, CorrectLockedBirth, etc.)
        │   ├── infrastructure/              # Adaptadores (JPA Entities, FastExcelSabanaExporter, S3, WeaningAlertScheduler)
        │   └── presentation/                # Controladores REST (/api/v1/*), JWT Filter y RanchContextInterceptor
        └── resources/db/migration/          # Migraciones Flyway (V1__init_schema.sql)
```

---

## Hoja de Ruta (Roadmap)

### Fase 1 — MVP de Fin de Semestre (Prioridad Core)
* [ ] Autenticación multi-tenant (**Cuenta Matriz** `OWNER`, **Cuentas Rama** `WORKER_BRANCH`, `VET_TECHNICIAN`, `ASSOCIATION_SUPERUSER`) y aislamiento por rancho.
* [ ] Ficha Individual del Animal (Reseña limpia: SINIIGA `484`, Folio interno, Folio UNO, distinción Rosa/Azul, navegación Padre/Madre/Crías y pedigrí de 4 generaciones).
* [ ] Registro de nacimientos con bloqueo antifraude a los 30 días, foto obligatoria y correcciones auditadas exclusivas para Asociación/Técnico.
* [ ] Módulo de Pesajes (Nacimiento, Destete, 120d, 150d, Evaluación y Circunferencia Escrotal) con cálculo de GDP y alerta automática de destete a los 75 días.
* [ ] Captura móvil offline en KMP Android + importación/exportación de la Sábana oficial de 24 columnas (`SABANA OCT 2026 .xlsx`).

### Fase 2 — Expansión Nutricional, Sanitaria y Financiera
* [ ] Calculador nutricional de 5 etapas con precios regionales editables y simulador de sustitución de ingredientes.
* [ ] Gestión de corrales, área de cuarentena y cartilla sanitaria completa (vacunas, vitaminas, selenio, desparasitación).
* [ ] Proyección financiera completa del ciclo productivo de 275 días y alertas automáticas de desecho materno.
* [ ] Compilación del cliente iOS (`iosApp`) y escalamiento nacional (México) e internacional (Centroamérica).

---

## Equipo de Desarrollo

* **[Lucas Patrick Castineiras]** — Ingeniería de Software (5.º Semestre) · [GitHub](https://github.com/Locas1000)
* **[Carlo Virgilio Buelna]** — Ingeniería de Software (5.º Semestre) · [GitHub](https://github.com/carlovirgil)
* **[Carlos Abdel Parra Oliva]** — Ingeniería de Software (3.º Semestre) · [GitHub](https://github.com/0Chuck)
* **[César Conrado González Muñoz]** — Ingeniería de Software (3.º Semestre) · [GitHub](https://github.com/)
* **[Santiago Orozco Reynosa]** — Ingeniería de Software (3.º Semestre) · [GitHub](https://github.com/)
* **[Jonathan Enciso Jiménez]** — Ingeniería en Inteligencia Artificial (3.º Semestre) · [GitHub](https://github.com/Purpleskull3747)
