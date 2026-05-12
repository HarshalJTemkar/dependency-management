# 🔍 Dependency Management AI Agent System

> **Production-grade, multi-agent Spring Boot application that analyses Maven project dependencies,
> detects outdated versions and breaking changes, and generates a rich single-page HTML report —
> powered by Spring AI, Ollama (local LLM) and OpenAI (fallback).**

---

## Table of Contents

1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [Architecture](#architecture)
   - [Multi-Agent Design](#multi-agent-design)
   - [Agent Pipeline Flow](#agent-pipeline-flow)
   - [Iteration Control System](#iteration-control-system)
4. [Project Structure](#project-structure)
5. [Data Contracts](#data-contracts)
6. [Configuration Reference](#configuration-reference)
7. [API & Endpoints](#api-endpoints)
8. [Report UI](#report-ui)
9. [i18n Support](#i18n-support)
10. [Error Handling](#error-handling)
11. [Caching Strategy](#caching-strategy)
12. [Idempotency Strategy](#idempotency-strategy)
13. [LLM Prompt System](#llm-prompt-system)
14. [Failure Handling Matrix](#failure-handling-matrix)
15. [Running the Application](#running-the-application)
16. [Environment Variables](#environment-variables)
17. [Future Extensibility](#future-extensibility)
18. [MVP Scope Boundaries](#mvp-scope-boundaries)

---

## Overview

The **Dependency Management AI Agent System** accepts a GitHub URL (public or private) or a
local file system path, reads the target project's `pom.xml`, extracts all Maven dependencies,
queries Maven Central for the latest stable versions, and uses an LLM to analyse breaking changes,
migration notes and severity — then renders everything as an interactive single-page HTML report.

### Key Capabilities

| Capability | Detail |
|---|---|
| Source types | GitHub Public, GitHub Private (PAT), Local FS |
| Build file support | Maven `pom.xml` (MVP) |
| Version registry | Maven Central REST API |
| AI analysis | Ollama `llama3.1:8b` (primary) → OpenAI `gpt-4o-mini` (fallback) |
| Fallback (no LLM) | Rule-based severity analyser |
| Report format | Single-page HTML — Thymeleaf + Apache ECharts |
| Languages | English (default), Deutsch |
| Caching | Caffeine in-process (60 min TTL, 1000 entry cap) |
| Tracing | MDC-based correlation ID per request |
| Idempotency | SHA-256 job-hash, in-memory `ConcurrentHashMap` |

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Core framework | Spring Boot | 3.4.5 |
| AI framework | Spring AI | 1.0.0 |
| Primary LLM | Ollama (`llama3.1:8b`) | Local inference |
| Fallback LLM | OpenAI (`gpt-4o-mini`) | Cloud API |
| UI templating | Thymeleaf | (managed by Boot) |
| Charts | Apache ECharts | 5.4.3 (CDN) |
| Reactive HTTP | Spring WebFlux / WebClient | (managed by Boot) |
| In-process cache | Caffeine | 3.1.8 |
| Build-file parser | Custom `MavenPomParser` | (internal) |
| Java version | Java 21 | Records, Sealed classes, Pattern Matching |
| Build tool | Maven | 3.x |

---

## Architecture

### Multi-Agent Design

The system is composed of **six specialised agents**, each with a single responsibility and its
own iteration loop. All orchestration flows exclusively through the `OrchestratorAgent`.

```
OrchestratorAgent  ──Tool Call──▶  SourceReaderAgent
                   ──Tool Call──▶  DependencyParserAgent
                   ──Tool Call──▶  VersionCheckerAgent
                   ──Tool Call──▶  AnalysisAgent
                   ──Tool Call──▶  ReportBuilderAgent
```

#### Agent Roster

| Agent | Role | Iteration Type | Max Passes |
|---|---|---|---|
| `OrchestratorAgent` | Master coordinator, retry + backoff | Retry Loop | 3 retries / agent |
| `SourceReaderAgent` | Reads `pom.xml` from GitHub or local FS | Deterministic | — |
| `DependencyParserAgent` | Extracts all dependencies from `pom.xml` | Reflection Loop | 3 |
| `VersionCheckerAgent` | Queries Maven Central for latest stable versions | Validation Loop | 2 |
| `AnalysisAgent` | LLM-powered breaking-change analysis | Multi-Pass Enrichment | 4 |
| `ReportBuilderAgent` | Assembles `DependencyReportDTO` + chart data | Self-Validation Loop | 2 |

#### Agent Rules

- Each agent has **Single Responsibility** — no cross-agent direct calls
- Every agent logs with `correlationId`, `agentName` and `iterationPass` via MDC
- Every agent has a **defined fallback** for when its primary strategy fails
- All max iteration counts are **configurable** via `application.yml`
- No infinite loops — every loop has a hard cap

---

### Agent Pipeline Flow

```
User Request (GitHub URL / local path)
         │
         ▼
┌─────────────────────────────────────────────┐
│              OrchestratorAgent              │
│  • Retry loop (max 3, exponential backoff)  │
│  • Checkpoint state per step                │
│  • Sets hasPartialResults on any fallback   │
└──────────┬──────────────────────────────────┘
           │
   ┌───────▼────────┐
   │ SourceReader   │  GitHub Public  → REST API v3 (no auth)
   │    Agent       │  GitHub Private → REST API v3 + PAT token
   └───────┬────────┘  Local         → Java NIO recursive scan
           │ raw pom.xml content
   ┌───────▼────────────┐
   │ DependencyParser   │  Pass 1: extract raw deps
   │     Agent          │  Pass 2: validate completeness
   │  (Reflection Loop) │  Pass 3: resolve ${property} placeholders
   └───────┬────────────┘
           │ List<DependencyInfo>
   ┌───────▼────────────┐
   │  VersionChecker    │  Pass 1: query Maven Central API
   │     Agent          │  Pass 2: confirm stable/non-prerelease
   │ (Validation Loop)  │
   └───────┬────────────┘
           │ List<VersionCheckResult>
   ┌───────▼────────────────────┐
   │      AnalysisAgent         │  Pass 1: batch analysis (10/batch)
   │  (Multi-Pass Enrichment)   │  Pass 2: deep dive CRITICAL + HIGH
   │                            │  Pass 3: consistency normalisation
   │                            │  Pass 4: JSON repair (conditional)
   └───────┬────────────────────┘
           │ List<AnalysisResult>
   ┌───────▼────────────────┐
   │   ReportBuilderAgent   │  Pass 1: assemble DependencyReportDTO
   │ (Self-Validation Loop) │  Pass 2: verify count consistency
   └───────┬────────────────┘
           │ DependencyReportDTO
   ┌───────▼────────────────┐
   │    Thymeleaf + ECharts │
   │   Single-Page Report   │
   └────────────────────────┘
```

---

### Iteration Control System

#### OrchestratorAgent — Retry Loop
- 3 retries per agent call (configurable)
- Exponential backoff: initial 1 s, multiplier ×2 (configurable)
- On exhaustion: pipeline continues with fallback data, sets `hasPartialResults = true`

#### DependencyParserAgent — Reflection Loop (max 3 passes)
| Pass | Action |
|---|---|
| 1 | Initial extraction of all raw dependencies |
| 2 | Self-validate: check `<dependencyManagement>`, `<profiles>`, `<plugins>` separately |
| 3 | Resolve `${property}` placeholders against `<properties>` block |
| Exit | Count stabilises between passes (confidence: COMPLETE) |

#### VersionCheckerAgent — Validation Loop (max 2 passes)
| Pass | Action |
|---|---|
| 1 | Fetch latest version from Maven Central Search API |
| 2 | Confirm no `alpha`/`beta`/`rc`/`SNAPSHOT`/`ea` markers remain |
| Exit | Zero `UNKNOWN` versions remaining |

#### AnalysisAgent — Multi-Pass Enrichment Loop (max 4 passes)
| Pass | Action |
|---|---|
| 1 | Batch analysis — 10 dependencies per batch, rough severity |
| 2 | Deep dive — CRITICAL + HIGH only, specific API changes |
| 3 | Consistency normalisation — normalize severity across all results |
| 4 | JSON repair — conditional, max 2 attempts, then RuleBasedAnalyzer fallback |
| Exit | Valid JSON + consistency check passed |

#### ReportBuilderAgent — Self-Validation Loop (max 2 passes)
| Pass | Action |
|---|---|
| 1 | Build `DependencyReportDTO` from `AnalysisResult` list |
| 2 | Validate: `criticalCount + highCount + mediumCount + lowCount + upToDate == totalDependencies` |
| Exit | All counts consistent + chart data non-empty |

---

## Project Structure

```
dependency-management/
├── pom.xml
└── src/
    └── main/
        ├── java/harshal/temkar/depmanagement/
        │   ├── DependencyManagementApplication.java   ← Entry point
        │   │
        │   ├── agent/                                 ← Six AI agents
        │   │   ├── OrchestratorAgent.java
        │   │   ├── SourceReaderAgent.java
        │   │   ├── DependencyParserAgent.java
        │   │   ├── VersionCheckerAgent.java
        │   │   ├── AnalysisAgent.java
        │   │   └── ReportBuilderAgent.java
        │   │
        │   ├── config/                                ← Spring configuration
        │   │   ├── AiConfig.java                      ← Ollama + OpenAI ChatClient beans
        │   │   ├── WebClientConfig.java               ← GitHub + Maven Central WebClients
        │   │   ├── CacheConfig.java                   ← Caffeine CacheManager
        │   │   ├── MessageSourceConfig.java           ← i18n + SessionLocaleResolver
        │   │   └── WebMvcConfig.java                  ← LocaleChangeInterceptor
        │   │
        │   ├── constants/
        │   │   └── Constants.java                     ← Single source of truth for strings
        │   │
        │   ├── controller/
        │   │   └── DependencyController.java          ← GET / + POST /analyze
        │   │
        │   ├── domain/
        │   │   ├── enums/
        │   │   │   ├── Severity.java                  ← CRITICAL / HIGH / MEDIUM / LOW / UP_TO_DATE
        │   │   │   ├── SourceType.java                ← GITHUB_PUBLIC / GITHUB_PRIVATE / LOCAL
        │   │   │   ├── DependencyScope.java           ← COMPILE / TEST / PROVIDED / RUNTIME …
        │   │   │   ├── AgentStatus.java               ← RUNNING / COMPLETED / FAILED
        │   │   │   ├── IterationStatus.java           ← NOT_STARTED / IN_PROGRESS / COMPLETED_*
        │   │   │   └── ErrorCode.java                 ← 18 error codes
        │   │   └── model/
        │   │       ├── DependencyInfo.java            ← Record: one parsed dependency
        │   │       ├── VersionCheckResult.java        ← Record: version diff metrics
        │   │       ├── AnalysisResult.java            ← Record: LLM analysis output
        │   │       ├── AnalysisJob.java               ← Record: idempotency state
        │   │       └── IterationContext.java          ← Record: iteration pass state
        │   │
        │   ├── dto/                                   ← Request/Response data transfer objects
        │   │   ├── AnalysisRequestDTO.java            ← Inbound form data (Java Record)
        │   │   ├── DependencyReportDTO.java           ← Full report passed to Thymeleaf
        │   │   ├── ChartDataDTO.java                  ← ECharts labels + values
        │   │   ├── AgentResponseDTO.java              ← Agent execution metadata
        │   │   └── ErrorResponseDTO.java              ← Structured error payload
        │   │
        │   ├── exception/                             ← Sealed exception hierarchy
        │   │   ├── DependencyManagementException.java ← Sealed base
        │   │   ├── AgentException.java
        │   │   ├── SourceReadException.java
        │   │   ├── ParseException.java
        │   │   ├── VersionCheckException.java
        │   │   └── GlobalExceptionHandler.java        ← @RestControllerAdvice
        │   │
        │   ├── filter/
        │   │   └── CorrelationIdFilter.java           ← MDC correlation ID per request
        │   │
        │   ├── parser/
        │   │   ├── DependencyParser.java              ← Strategy interface (extensibility hook)
        │   │   └── MavenPomParser.java                ← DOM-based pom.xml parser
        │   │
        │   ├── service/
        │   │   ├── AnalysisOrchestrationService.java  ← Idempotency + pipeline coordination
        │   │   └── MavenCentralService.java           ← Version lookup + diff computation
        │   │
        │   ├── tool/                                  ← Spring AI @Tool annotated components
        │   │   ├── GitHubTool.java                    ← GitHub REST API v3 (public + private)
        │   │   ├── LocalFileSystemTool.java           ← Java NIO local file reading
        │   │   ├── MavenVersionTool.java              ← Maven Central + Caffeine cache
        │   │   ├── LlmAnalysisTool.java               ← Ollama → OpenAI fallback execution
        │   │   ├── IterationControlTool.java          ← Pass lifecycle management
        │   │   ├── JsonRepairTool.java                ← LLM JSON self-repair
        │   │   └── BatchSplitterTool.java             ← Batch splitting for analysis
        │   │
        │   └── util/
        │       ├── VersionComparator.java             ← Semantic version comparison + diff
        │       ├── CorrelationIdUtil.java             ← MDC utilities
        │       └── RuleBasedAnalyzer.java             ← Final fallback — no LLM required
        │
        └── resources/
            ├── application.yml                        ← All configuration (fully externalized)
            ├── prompts/                               ← StringTemplate prompt files
            │   ├── orchestrator-prompt.st
            │   ├── parser-pass1-prompt.st
            │   ├── parser-pass2-prompt.st
            │   ├── parser-pass3-prompt.st
            │   ├── version-validation-prompt.st
            │   ├── analysis-pass1-prompt.st           ← Batch severity analysis
            │   ├── analysis-pass2-prompt.st           ← Deep dive CRITICAL/HIGH
            │   ├── analysis-pass3-prompt.st           ← Consistency normalisation
            │   ├── analysis-repair-prompt.st          ← JSON self-repair
            │   └── report-validation-prompt.st
            ├── templates/
            │   ├── index.html                         ← Analysis input form
            │   └── report.html                        ← Single-page HTML report
            ├── static/
            │   ├── css/report.css
            │   └── js/charts.js                       ← ECharts initialisation
            └── i18n/
                ├── messages.properties                ← English labels
                └── messages_de.properties             ← German labels
```

---

## Data Contracts

### Step 1 — Inbound Request (`AnalysisRequestDTO`)

```java
record AnalysisRequestDTO(
    @NotNull SourceType sourceType,  // GITHUB_PUBLIC | GITHUB_PRIVATE | LOCAL
    String repoUrl,                  // GitHub URL or null
    String localPath,                // absolute FS path or null
    String githubToken,              // PAT or null (private only)
    String language,                 // "en" | "de"
    String correlationId             // auto-generated UUID if blank
)
```

### Step 2 — Parsed Dependencies (`DependencyInfo`)

```java
record DependencyInfo(
    String groupId,
    String artifactId,
    String currentVersion,           // property placeholders resolved
    DependencyScope scope,
    boolean isManaged,
    String resolvedFrom              // "dependencies" | "dependencyManagement"
                                     // | "profile" | "plugin"
)
```

### Step 3 — Version Check (`VersionCheckResult`)

```java
record VersionCheckResult(
    DependencyInfo dependency,
    String latestVersion,            // "UNKNOWN" if Maven Central unreachable
    boolean isStable,
    boolean updateAvailable,
    int versionsBehind,
    int majorBehind,
    int minorBehind,
    int patchBehind
)
```

### Step 4 — Analysis (`AnalysisResult`)

```java
record AnalysisResult(
    VersionCheckResult versionCheckResult,
    Severity severity,               // CRITICAL | HIGH | MEDIUM | LOW | UP_TO_DATE
    String breakingChanges,
    String migrationNotes,
    String releaseNotesUrl,
    int analysisPass,                // which pass produced this result
    boolean isLlmAnalyzed            // false = RuleBasedAnalyzer used
)
```

### Step 5 — Full Report (`DependencyReportDTO`)

```java
record DependencyReportDTO(
    String correlationId,
    Instant generatedAt,
    String sourceInfo,
    int totalDependencies,
    int upToDate,
    int outdated,
    int criticalCount,
    int highCount,
    int mediumCount,
    int lowCount,
    boolean hasPartialResults,       // true if any fallback was used
    List<AnalysisResult> dependencies,
    ChartDataDTO severityChartData,
    ChartDataDTO statusChartData,
    ChartDataDTO updatesChartData
)
```

---

## Configuration Reference

All configuration is externalized to `application.yml`. No hardcoded values anywhere in code.

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434   # Ollama server URL
      chat:
        model: llama3.1:8b
        options:
          temperature: 0.1              # Deterministic output
          num-predict: 2048
    openai:
      api-key: ${OPENAI_API_KEY:}        # Optional — fallback only
      chat:
        model: gpt-4o-mini

app:
  github:
    api-base-url: https://api.github.com
    token: ${GITHUB_TOKEN:}              # Optional global PAT
    timeout-seconds: 30

  maven:
    central-search-url: https://search.maven.org/solrsearch/select
    timeout-seconds: 15

  ai:
    primary-provider: ollama
    fallback-provider: openai
    max-retries: 3
    timeout-seconds: 60
    iterations:
      parser:
        max-passes: 3
        confidence-threshold: HIGH
      version-checker:
        max-passes: 2
        exclude-prerelease: true
        exclude-snapshot: true
      analysis:
        max-passes: 4
        batch-size: 10
        deep-analysis-severities: [CRITICAL, HIGH]
        json-repair-max-attempts: 2
      report-builder:
        max-passes: 2
      orchestrator:
        max-retries: 3
        backoff-initial-seconds: 1
        backoff-multiplier: 2

  cache:
    version-check-ttl-minutes: 60
    max-size: 1000
```

---

## API & Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Renders the analysis input form (`index.html`) |
| `POST` | `/analyze` | Runs the full pipeline and renders the report (`report.html`) |
| `GET` | `/actuator/health` | Spring Actuator health check |
| `GET` | `/actuator/info` | Application info |
| `GET` | `/actuator/metrics` | Metrics endpoint |

### POST /analyze — Form Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `sourceType` | `SourceType` | ✅ | `GITHUB_PUBLIC`, `GITHUB_PRIVATE`, or `LOCAL` |
| `repoUrl` | `String` | GitHub only | Full GitHub URL e.g. `https://github.com/owner/repo` |
| `localPath` | `String` | LOCAL only | Absolute path to project root or `pom.xml` directly |
| `githubToken` | `String` | Private only | GitHub Personal Access Token |
| `language` | `String` | ❌ | `en` (default) or `de` |
| `correlationId` | `String` | ❌ | Auto-generated UUID if not supplied |

### Request Header

| Header | Description |
|---|---|
| `X-Correlation-ID` | Optional. If provided, overrides auto-generated ID. Echoed in response. |

---

## Report UI

The single-page HTML report is generated by Thymeleaf and rendered directly in the browser.

### Layout

```
┌─────────────────────────────────────────────────────┐
│ HEADER                                               │
│ "Dependency Analysis Report"           [EN] [DE]     │
│ Correlation ID: xxx │ Generated: timestamp │ Source  │
│ [⚠ WARNING BANNER — shown if hasPartialResults]     │
├─────────────────────────────────────────────────────┤
│ SUMMARY CARDS (4 horizontal)                         │
│  [Total: 47]  [Up-to-Date: 12]  [Outdated: 35]      │
│  [Critical: 8]                                       │
├───────────────────────┬─────────────────────────────┤
│  PIE CHART            │  HORIZONTAL BAR CHART        │
│  Severity Distribution│  Count per Severity Level    │
│  (ECharts)            │  (ECharts)                   │
├───────────────────────┴─────────────────────────────┤
│ SEARCH + SEVERITY FILTER                             │
├─────────────────────────────────────────────────────┤
│ DEPENDENCY TABLE (full width, sortable)              │
│ Group ID │ Artifact ID │ Current │ Latest │ Severity │
│ Breaking Changes │ Migration Notes │ Source │ Badge  │
│                                                      │
│  Severity badge colours:                             │
│    CRITICAL = red  HIGH = orange  MEDIUM = yellow    │
│    LOW = blue      UP_TO_DATE = green                │
│  Analysis badge: [AI Analyzed] vs [Rule Based]       │
├─────────────────────────────────────────────────────┤
│ FOOTER                                               │
│ Generated by Dependency Management AI Agent System   │
└─────────────────────────────────────────────────────┘
```

### Interactive Features

- **Language toggle** — EN / DE top-right (via `?lang=` session locale)
- **Full-text search** — live filter across all table columns
- **Severity filter** — dropdown filters table to a specific severity level
- **Sortable columns** — click any column header to sort ascending/descending
- **Release notes link** — clickable 📋 icon when `releaseNotesUrl` is present
- **Responsive layout** — summary cards and charts adapt to viewport width

---

## i18n Support

Two bundles are provided under `src/main/resources/i18n/`:

| Key prefix | Description |
|---|---|
| `report.title` | Page title |
| `report.subtitle.*` | Header metadata labels |
| `report.warning.partial` | Partial-results warning banner |
| `report.card.*` | Summary card labels |
| `report.severity.*` | Severity display names |
| `report.table.*` | Dependency table column headers |
| `report.chart.*` | Chart section titles |
| `report.badge.*` | AI Analyzed / Rule Based badges |
| `report.footer` | Footer text |

**Locale switching** is handled by `SessionLocaleResolver` + `LocaleChangeInterceptor` on the
`lang` request parameter. Clicking `[EN]` or `[DE]` in the report header sets the session locale.

---

## Error Handling

### Exception Hierarchy (Sealed)

```
DependencyManagementException  (sealed base — RuntimeException)
  ├── AgentException            ← agent pipeline failures
  ├── SourceReadException       ← GitHub / local FS read failures
  ├── ParseException            ← pom.xml malformed XML
  └── VersionCheckException     ← Maven Central errors
```

Every exception carries:
- `ErrorCode` — machine-readable classification
- `message` — human-readable description
- `agentName` — which agent raised it
- `iterationPass` — which pass number failed (0 if not in a loop)

### Error Code Catalogue

| Code | HTTP Status | Scenario |
|---|---|---|
| `INVALID_INPUT` | 400 | Missing required fields, bad URL format |
| `POM_XML_MALFORMED` | 400 | XML parsing failed |
| `GITHUB_AUTH_FAILED` | 401 | Invalid or missing PAT token |
| `SOURCE_NOT_FOUND` | 404 | Repo or file does not exist |
| `GITHUB_REPO_NOT_FOUND` | 404 | GitHub 404 response |
| `POM_XML_NOT_FOUND` | 404 | `pom.xml` absent in repo or path |
| `GITHUB_RATE_LIMITED` | 429 | GitHub API rate limit hit |
| `IDEMPOTENCY_CONFLICT` | 409 | Same analysis already running |
| `AGENT_FAILED` | 500 | Agent exhausted all retries |
| `LLM_UNAVAILABLE` | 500 | Both Ollama and OpenAI failed |
| `LLM_JSON_PARSE_FAILED` | 500 | JSON repair also failed |
| `MAVEN_CENTRAL_UNREACHABLE` | 500 | Maven Central timeout |

### Error Response Shape (`ErrorResponseDTO`)

```json
{
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "POM_XML_NOT_FOUND",
  "message": "pom.xml not found in owner/repo",
  "timestamp": "2026-05-11T10:30:00Z",
  "agentName": "SourceReaderAgent",
  "iterationPass": 0
}
```

Raw stack traces are **never** returned to clients.

---

## Caching Strategy

| Property | Value |
|---|---|
| Cache name | `versionCheckCache` |
| Library | Caffeine (in-process, zero infrastructure) |
| TTL | 60 minutes (configurable) |
| Max size | 1000 entries (configurable) |
| Cache key | `groupId:artifactId` e.g. `org.springframework:spring-core` |
| Cache value | Latest stable version string |
| Cache layer | `MavenCentralService.getLatestVersion()` via `@Cacheable` |
| Stats | Caffeine `recordStats()` enabled — visible via Actuator metrics |

**Rationale:** Maven Central versions do not change within 60 minutes. For a large `pom.xml`
(50+ dependencies), caching prevents 50+ outbound HTTP calls on every analysis and avoids
Maven Central rate-limiting.

---

## Idempotency Strategy

| Property | Detail |
|---|---|
| Key | `SHA-256(sourceType + "\|" + sourceUrl + "\|" + minuteTruncatedTimestamp)` |
| Storage | `ConcurrentHashMap<String, AnalysisJob>` — in-memory, no database |
| Conflict | Same key while status is `RUNNING` → `409 Conflict` with existing `correlationId` |
| Cleanup | Entry removed on `COMPLETED` or `FAILED` — no stale state |
| Scope | Per JVM instance (MVP — no distributed cache) |

---

## LLM Prompt System

All prompts are stored as **StringTemplate (`.st`) files** under `src/main/resources/prompts/`.
They are loaded at runtime via `ClassPathResource`. No inline prompt strings exist in Java code.

| File | Agent | Purpose |
|---|---|---|
| `orchestrator-prompt.st` | OrchestratorAgent | Master flow instructions |
| `parser-pass1-prompt.st` | DependencyParserAgent | Extract raw dependencies |
| `parser-pass2-prompt.st` | DependencyParserAgent | Self-validate completeness |
| `parser-pass3-prompt.st` | DependencyParserAgent | Resolve property placeholders |
| `version-validation-prompt.st` | VersionCheckerAgent | Confirm stable version |
| `analysis-pass1-prompt.st` | AnalysisAgent | Batch rough severity analysis |
| `analysis-pass2-prompt.st` | AnalysisAgent | Deep dive CRITICAL + HIGH |
| `analysis-pass3-prompt.st` | AnalysisAgent | Normalize consistency |
| `analysis-repair-prompt.st` | AnalysisAgent | Fix malformed JSON output |
| `report-validation-prompt.st` | ReportBuilderAgent | Validate report consistency |

### Prompt Design Rules

- All prompts enforce `"Return a JSON array only. No explanation. No markdown."`
- Temperature is set to `0.1` for deterministic, factual output
- Every prompt instructs the LLM on the exact JSON schema expected
- Repair prompt appends: `"Fix the JSON and return only valid JSON. No explanation."`

---

## Failure Handling Matrix

| Failure | Strategy |
|---|---|
| Ollama unavailable | Auto-fallback to OpenAI |
| OpenAI API key missing / failed | Fallback to `RuleBasedAnalyzer` |
| GitHub API rate limited | Return `429` with `Retry-After` concept via `ErrorCode.GITHUB_RATE_LIMITED` |
| GitHub token invalid | Return `401` with `GITHUB_AUTH_FAILED` |
| GitHub repo not found | Return `404` with `GITHUB_REPO_NOT_FOUND` |
| `pom.xml` not found | Return `404` with `POM_XML_NOT_FOUND` |
| `pom.xml` malformed XML | Return `400` with `POM_XML_MALFORMED` |
| Maven Central unreachable | Mark version `UNKNOWN`, continue pipeline, set `hasPartialResults = true` |
| LLM JSON parse failure | Trigger JSON Repair Pass (max 2 attempts), then `RuleBasedAnalyzer` |
| LLM max iterations reached | Use `RuleBasedAnalyzer` fallback, set `isLlmAnalyzed = false` |
| Agent timeout / failure | Mark agent `FAILED`, pipeline continues, set `hasPartialResults = true` |
| Idempotency conflict | Return `409` with existing job `correlationId` |
| Missing correlation ID | Auto-generate UUID, log `WARN` |

**Key principle:** The pipeline **always completes** and **always produces a report**, even when
every external dependency fails. `RuleBasedAnalyzer` is the guaranteed last resort.

---

## Running the Application

### Prerequisites

| Requirement | Notes |
|---|---|
| Java 21 | Required — uses Records, Sealed classes, Pattern Matching |
| Maven 3.x | For build and dependency resolution |
| Ollama | Optional but recommended — `ollama pull llama3.1:8b` |
| OpenAI API key | Optional — only needed as LLM fallback |
| Internet access | For Maven Central API calls |

### Quick Start

```bash
# 1. Clone and enter the project
cd dependency-management

# 2. (Recommended) Start Ollama with the required model
ollama pull llama3.1:8b
ollama serve

# 3. Build the project
mvn clean package -DskipTests

# 4. Run
java --enable-preview -jar target/dependency-management.jar

# 5. Open the browser
open http://localhost:8080
```

### Running Without Ollama (Rule-Based Mode)

If Ollama is not available and no OpenAI key is set, the system falls back automatically to
`RuleBasedAnalyzer`. All features work — severity is computed from version-diff metrics only,
and `isLlmAnalyzed = false` on every result.

```bash
java --enable-preview -jar target/dependency-management.jar
# LLM calls will fail gracefully → RuleBasedAnalyzer fallback activates
```

### Running in IDE (Eclipse / IntelliJ)

1. Import as Maven project
2. Ensure Java 21 SDK is selected
3. Add VM option: `--enable-preview`
4. Run `DependencyManagementApplication`

---

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `OPENAI_API_KEY` | ❌ | OpenAI API key for fallback LLM |
| `GITHUB_TOKEN` | ❌ | Default GitHub PAT (can be overridden per request) |

These are referenced in `application.yml` via `${OPENAI_API_KEY:}` and `${GITHUB_TOKEN:}`.
Empty strings are safe defaults — no startup failure if absent.

---

## Severity Scoring Model

| Severity | Trigger | Indicator |
|---|---|---|
| `CRITICAL` | 2+ major versions behind, or CVE mentioned by LLM | 🔴 Red badge |
| `HIGH` | Exactly 1 major version behind | 🟠 Orange badge |
| `MEDIUM` | Minor version behind (same major) | 🟡 Yellow badge |
| `LOW` | Patch version behind only | 🔵 Blue badge |
| `UP_TO_DATE` | Already on latest stable version | 🟢 Green badge |

The `RuleBasedAnalyzer` derives severity purely from `majorBehind`, `minorBehind`, `patchBehind`
metrics. The `AnalysisAgent` enriches this with LLM knowledge of specific breaking changes.

---

## Future Extensibility

The following **extension interfaces** are present in the codebase as stubs, ready for future
implementation without breaking existing agents:

| Interface | MVP | Future |
|---|---|---|
| `DependencyParser` | `MavenPomParser` | `GradleParser`, `NpmParser`, `PipParser`, `GoModParser` |
| `SourceReader` | `GitHubTool`, `LocalFileSystemTool` | GitLab, Bitbucket, Azure DevOps |
| `VersionRegistry` | `MavenCentralService` | npm Registry, PyPI, Go Proxy |
| `MigrationAgent` | — | Auto-generate code migration patches |
| `LanguageMigrationAgent` | — | Java → Kotlin, Python → Java |

---

## MVP Scope Boundaries

The following features are **explicitly out of scope** for this MVP:

| Feature | Status |
|---|---|
| CI/CD pipeline integration | ❌ Out of scope |
| CVE / vulnerability database scanning | ❌ Out of scope |
| Auto-code patching | ❌ Out of scope |
| Gradle / NPM / Pip / Go parsers | ❌ Out of scope |
| Database persistence | ❌ Out of scope |
| User authentication / authorization | ❌ Out of scope |
| Docker / Kubernetes configuration | ❌ Out of scope |
| Multi-repo batch analysis | ❌ Out of scope |
| Webhook / scheduled analysis | ❌ Out of scope |
| Email / Slack notifications | ❌ Out of scope |

---

## MDC Logging Format

All log messages follow this structured format, enabling correlation across distributed systems:

```
[correlationId=<uuid>][agentName=<agent>][pass=<n>] LEVEL  logger - message
```


| MDC Key | Content |
|---|---|
| `correlationId` | Per-request UUID, set by `CorrelationIdFilter` |
| `agentName` | Constants: `OrchestratorAgent`, `SourceReaderAgent`, etc. |
| `iterationPass` | Current loop pass number (0 if not in a loop) |

---

## Author

**Harshal Temkar**  
Version: `1.0.0-SNAPSHOT`  
Built with Spring Boot 3.4.5 + Spring AI 1.0.0 + Java 21
