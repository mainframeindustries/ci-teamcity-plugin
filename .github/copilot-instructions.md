# Datadog CI TeamCity Integration - Copilot Instructions

## Project Overview

This is a TeamCity server plugin that integrates with Datadog's CI Visibility product. The plugin monitors TeamCity build chains and sends pipeline and job data to Datadog's webhook intake API for CI/CD observability.

**Key Functionality:**
- Listens to TeamCity build lifecycle events (finish, interrupt)
- Processes build chains (composite builds and dependencies)
- Extracts Git information from builds
- Transforms TeamCity build data into Datadog webhook payloads
- Sends data asynchronously to Datadog's webhook intake endpoints

## Technology Stack

- **Language:** Java 8
- **Build Tool:** Maven 3.2.x
- **Framework:** Spring Framework (dependency injection via annotations)
- **TeamCity API:** Version 2021.2
- **Testing:** JUnit, Mockito, AssertJ
- **Serialization:** Jackson (JSON)
- **HTTP Client:** Spring RestTemplate

## Project Structure

```
ci-teamcity-plugin/
├── pom.xml                          # Parent POM (multi-module project)
├── teamcity-plugin.xml              # Plugin descriptor
├── datadog-ci-integration-server/   # Server-side plugin module
│   ├── pom.xml
│   ├── src/main/java/               # Production code
│   │   └── jetbrains/buildServer/com/datadog/teamcity/plugin/
│   │       ├── DatadogServerAdapter.java       # Event listener (BuildServerAdapter)
│   │       ├── BuildChainProcessor.java        # Core logic for processing builds
│   │       ├── DatadogClient.java              # HTTP client for Datadog API
│   │       ├── GitInformationExtractor.java    # Extracts git metadata
│   │       ├── ProjectHandler.java             # Project-level configuration
│   │       ├── DatadogConfiguration.java       # Spring bean configuration
│   │       ├── BuildUtils.java                 # Build utility methods
│   │       └── model/entities/                 # Data models
│   │           ├── Webhook.java                # Base webhook class
│   │           ├── PipelineWebhook.java        # Pipeline-level events
│   │           ├── JobWebhook.java             # Job-level events
│   │           └── GitInfo.java                # Git metadata
│   ├── src/main/resources/
│   │   └── META-INF/build-server-plugin-datadog-ci-integration.xml  # Spring config
│   └── src/test/                    # Unit tests
└── build/                           # Plugin assembly module
    └── plugin-assembly.xml
```

## Architecture & Design Patterns

### Spring Dependency Injection
- Uses `@Component` annotation for Spring beans
- Constructor-based dependency injection (Spring autowiring)
- Component scanning enabled in Spring config: `jetbrains.buildServer.com.datadog.teamcity.plugin`

### Key Components

1. **DatadogServerAdapter** (`@Component`)
   - Extends `BuildServerAdapter` from TeamCity API
   - Registers with `EventDispatcher<BuildServerListener>`
   - Triggers on `buildFinished()` and `buildInterrupted()` events
   - Filters for final composite builds only (ignores intermediate builds)

2. **BuildChainProcessor** (`@Component`)
   - Core processing logic for build chains
   - Traverses dependency graphs to collect all jobs
   - Creates `PipelineWebhook` and `JobWebhook` payloads
   - Handles build status mapping, timing calculations, and error extraction

3. **DatadogClient**
   - Async webhook sending via `ExecutorService`
   - Retry logic with exponential backoff
   - Uses `RestTemplate` for HTTP communication
   - Headers: `DD-API-KEY`, `DD-CI-PROVIDER-NAME` (always "teamcity")

4. **GitInformationExtractor** (`@Component`)
   - Parses git commit info from build parameters
   - Extracts: commit SHA, branch, tag, author, committer, message
   - Handles TeamCity VCS root parameters

5. **ProjectHandler** (`@Component`)
   - Reads plugin configuration from TeamCity project parameters
   - Checks if plugin is enabled per project
   - Retrieves API key and Datadog site configuration

### Data Flow

```
TeamCity Build Event
    ↓
DatadogServerAdapter (filters composite builds)
    ↓
BuildChainProcessor.process()
    ↓
1. Extract Git info
2. Traverse build dependencies
3. Create Pipeline + Job webhooks
4. Calculate timings, statuses
    ↓
DatadogClient.sendWebhooksAsync()
    ↓
POST to https://webhook-intake.{ddSite}/api/v2/webhook
```

## Coding Standards

### File Headers
All Java files must include the Apache 2.0 license header:
```java
/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */
```

### Annotations
- Use `@Nonnull` and `@Nullable` for nullability annotations (javax.annotation)
- Use `@Component` for Spring-managed beans
- Use `@VisibleForTesting` for test-only visibility
- Jackson annotations for JSON serialization: `@JsonProperty`, `@JsonIgnore`

### Logging
- Use IntelliJ's `Logger` class: `com.intellij.openapi.diagnostic.Logger`
- Initialize logger: `Logger.getInstance(ClassName.class.getName())`
- Use `String.format()` or `format()` for log messages with variables

### Code Style
- Static imports for utility methods (e.g., `BuildUtils.*`)
- Final fields for immutable dependencies
- Constructor injection for dependencies
- Static factory methods where appropriate
- Utility classes should be `final` with private constructor

### Testing
- Unit tests in matching package structure under `src/test/java/`
- Use Mockito for mocking TeamCity API objects
- Use AssertJ for fluent assertions
- Test resources (JSON samples) in `src/test/resources/`

## Important Domain Concepts

### Build Types
- **Composite Build:** A build that triggers other builds (like a pipeline)
- **Build Chain:** The dependency graph of builds
- **Snapshot Dependency:** TeamCity's mechanism for build dependencies
- **Partial Retry:** When only failed builds in a chain are re-run

### Webhook Types
- **Pipeline Webhook:** Represents the entire build chain (composite build)
- **Job Webhook:** Represents individual builds within the chain

### Build Identification
- `uniqueId`: TeamCity build promotion ID
- `pipelineId`: Composite build ID
- Build names constructed from configuration name + build number

### Timing
- `queueTime`: Time build waited in queue before starting
- `start`/`end`: RFC3339 formatted timestamps
- Duration calculations include offset for partial retries

### Status Mapping
TeamCity statuses map to Datadog statuses:
- `SUCCESS` → `success`
- `FAILURE` → `error`
- `ERROR` → `error`
- Interrupted builds → `canceled`

## Configuration

Plugin is configured via TeamCity project parameters:
- `datadog.plugin.enabled`: Enable/disable plugin (boolean)
- `datadog.api.key`: Datadog API key for authentication
- `datadog.site`: Datadog site (e.g., "datadoghq.com", "datadoghq.eu")

## Build & Development

### Building
```bash
mvn package
```
Output: `build/target/datadog-ci-integration.zip`

### Running Tests
```bash
mvn test
```

### Local Testing
1. Build the plugin ZIP
2. Upload to TeamCity server via Admin UI
3. Configure project parameters
4. Trigger builds and observe logs

### Debugging
- TeamCity server logs: Check for DatadogServerAdapter, BuildChainProcessor log entries
- Plugin logs use TeamCity's logging system (IntelliJ Logger)
- Test webhook payloads against JSON schemas in test resources

## Common Tasks

### Adding New Webhook Fields
1. Update model classes in `model/entities/` (add `@JsonProperty`)
2. Update `BuildChainProcessor` to populate new fields
3. Add test cases with expected JSON output
4. Update test resource JSON files if needed

### Modifying Build Processing Logic
1. Changes typically go in `BuildChainProcessor`
2. Update `BuildUtils` for shared utility methods
3. Ensure composite build filtering logic remains in `DatadogServerAdapter`
4. Write unit tests using `MockBuild` and `TestUtils`

### Updating Git Extraction
1. Modify `GitInformationExtractor`
2. Check TeamCity VCS parameter naming conventions
3. Test with different VCS root configurations

### Changing HTTP Client Behavior
1. Update `DatadogClient` for request/response handling
2. Modify retry logic in `RetryInformation` if needed
3. Update headers or URL construction as required

## Anti-Patterns to Avoid

❌ Don't process non-composite builds or intermediate builds (wastes resources)
❌ Don't block the TeamCity server thread (use async execution)
❌ Don't log sensitive information (API keys, tokens)
❌ Don't create builds without checking if plugin is enabled
❌ Don't modify TeamCity state (plugin is read-only observer)

## Dependencies Notes

- TeamCity SDK dependencies are `provided` scope (available at runtime)
- Spring Framework comes with TeamCity
- Jackson is included in TeamCity's classpath
- Avoid adding heavy dependencies (affects plugin size and server performance)

## Resources

- [TeamCity Plugin Development Guide](https://plugins.jetbrains.com/docs/teamcity/getting-started-with-plugin-development.html)
- [Datadog CI Visibility - TeamCity Setup](https://docs.datadoghq.com/continuous_integration/pipelines/teamcity/)
- TeamCity API: Server API, BuildServerAdapter, EventDispatcher

## Version Information

- Current Version: 0.0.5
- Target TeamCity Version: 2021.2
- Java Version: 8
- License: Apache 2.0
