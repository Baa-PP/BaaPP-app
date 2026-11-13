# BaaPP (OviApp) — App Móvil Offline-First (Kotlin Multiplatform)

> **"Tu rebaño en orden, con o sin señal."**  
> **Proyecto Integrador — AgroTech Challenge (TECOWEEK Winter Edition 2026)**  
> **Universidad Autónoma de Guadalajara (UAG)**

---

## Descripción del Proyecto

**BaaPP (OviApp)** es una plataforma mexicana *mobile-first* y *offline-first* de gestión zootécnica, trazabilidad genealógica y control financiero para ranchos ovinos (de 10 a 1,000+ cabezas), diseñada para sustituir software extranjero costoso y complejo (*OvisWebs*) con una experiencia adaptada al campo mexicano.

El ecosistema está separado en **tres repositorios especializados**:
* **`BaaPP-app` (este repositorio):** Aplicación móvil nativa **Kotlin Multiplatform (KMP) + Jetpack Compose** para captura ultrarrápida en corrales sin cobertura celular (`SQLDelight` + `WorkManager`).
* **[`BaaPP-backend`](https://github.com/Baa-PP/BaaPP-backend):** API central en **Kotlin + Spring Boot 3.4** bajo **Onion Architecture**, base de datos **PostgreSQL 16 (Amazon RDS)**, motor **FastExcel** para la Sábana de 24 columnas y reglas antifraude.
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
   * **Correcciones Auditadas:** Solo los roles `ASSOCIATION_SUPERUSER` o `VET_TECHNICIAN` pueden corregir errores de captura ("error de dedo"), previa evidencia fotográfica.
   * **Ciclo de Lactancia y Alerta a los 75 Días:** Pesajes en Nacimiento, Destete (60–75d), 120d, 150d y Evaluación con cálculo automático de **Ganancia Diaria de Peso (GDP)** y edad dinámica en meses/días. Al cumplirse `Fecha de Nacimiento + 75 días` (configurable), el sistema emite alerta push (FCM); al confirmar el destete, la madre pasa automáticamente de *Lactancia* a *Mantenimiento (Vacía)* y cambia su fórmula alimenticia.
3. **Módulo 3 — Cartilla Sanitaria, Corrales y Cuarentena:**
   * Historial por animal o lote en 4 rubros: **Vacunación**, **Vitaminación**, **Aplicación de Selenio** y **Desparasitación**.
   * Ubicación por corral y transferencia al área de **Cuarentena / Enfermería** del propio rancho (estado *"En tratamiento"* con diagnóstico y medicación).
4. **Módulo 4 — Manejo Alimenticio (5 Etapas) y Proyección Productiva:**
   * Formulación para **Lactancia** (~18% PC), **Crecimiento** (*creep feeding*), **Desarrollo** (recría), **Engorda** y **Mantenimiento**, con catálogo de granos/forrajes con `% de proteína` constante precargado y precios regionales (`$/kg TCO`) editables por productor.
   * Fórmulas deterministas del modelo del **Mtro. Alejandro Meléndez** (ciclo productivo de 275 días): `% Preñez`, `% Partos`, `Prolificidad`, `Intervalo entre Ciclos`, `Días de Engorda` y conversión `MS a TCO`.
5. **Módulo 5 — Mortalidad Histórica y Detección de Vientres Improductivos:**
   * **Prohibición de borrado físico (`DELETE`):** Las bajas transicionan a `status = 'DECEASED'` conservando fecha, causa y etapa para estadísticas de mortalidad y conciliación de chips SINIIGA.
   * **Evaluación Materna Automática:** Identifica vientres improductivos (infertilidad o mortalidad consecutiva de camadas) para recomendar su envío a rastro/desecho.

---

## Stack Tecnológico Móvil (`BaaPP-app`)

| Submódulo | Tecnología Seleccionada | Librerías y Módulos Clave | Justificación en Campo |
| :--- | :--- | :--- | :--- |
| **`shared` (Núcleo Multiplataforma)** | **Kotlin Multiplatform (KMP)** | SQLDelight (SQLite), Ktor Client, Kotlinx Coroutines & Flow, Kotlinx Serialization, Koin | 100% de lógica de negocio, base de datos local offline y cola de sincronización compartida entre Android y futura expansión a iOS (`SwiftUI`). |
| **`androidApp` (UI Nativa Android)** | **Jetpack Compose (Material 3)** | Android WorkManager, Coil, compresión de imagen WebP/JPEG (~150–200 KB) | Bajo consumo de RAM y 60 FPS en teléfonos Android económicos; botones táctiles grandes (`>=56dp`) y alto contraste para lectura bajo el sol en el corral. |

---

## Protocolo de Sincronización Offline-First

1. **Captura Local en Corral:** La app KMP inserta registros en **SQLDelight** usando identificadores `UUIDv7` generados en el cliente, comprime las fotos de evidencia localmente (~150–200 KB WebP/JPEG) y encola la mutación en `pending_sync_events` (`sync_status = 'PENDING'`).
2. **Carga Directa de Evidencia a S3:** Al detectar red (`WorkManager` con `NetworkType.CONNECTED`), `OfflineSyncEngine` solicita Pre-Signed PUT URLs a [`BaaPP-backend`](https://github.com/Baa-PP/BaaPP-backend), sube las fotos directamente a Amazon S3 y adjunta el `s3Key` al evento.
3. **Envío por Lotes Idempotente:** El cliente envía el lote ordenado de mutaciones a `POST /api/v1/sync/batch`.
4. **Deduplicación en Servidor:** El backend verifica cada `mutation_id` contra `processed_sync_mutations` garantizando cero duplicados ante reintentos de red.

---

## Estructura del Repositorio (`BaaPP-app`)

```text
BaaPP-app/
├── shared/                                  # Módulo Kotlin Multiplatform compartido (Android & futuro iOS)
│   ├── src/commonMain/
│   │   ├── kotlin/com/agrotech/oviapp/
│   │   │   ├── domain/model/                # Modelos (Animal, enums ortogonales) y calculadoras locales
│   │   │   ├── data/local/                  # Repositorios sobre SQLDelight
│   │   │   ├── data/remote/                 # Cliente Ktor HTTP, refresh JWT y subida S3
│   │   │   ├── sync/                        # OfflineSyncEngine (cola de salida idempotente)
│   │   │   └── presentation/                # ViewModels compartidos (StateFlow<UiState>)
│   │   └── sqldelight/com/agrotech/oviapp/db/
│   │       └── OviAppDatabase.sq            # Esquema SQLite local (local_animals, pending_sync_events)
│   └── build.gradle.kts
├── androidApp/                              # Aplicación nativa Android (Jetpack Compose)
│   ├── src/main/
│   │   ├── kotlin/com/agrotech/oviapp/android/
│   │   │   └── MainActivity.kt              # Punto de entrada Compose (botones >=56dp, Rosa/Azul)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
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
