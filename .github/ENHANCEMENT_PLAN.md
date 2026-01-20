# TeamCity Plugin Enhancement Project Plan

**Status:** Planning Phase  
**Last Updated:** January 20, 2026

## Overview
Enhance the Datadog CI TeamCity Integration plugin to support:
1. Build step timing information
2. Multiple VCS systems (Perforce)
3. Manual triggers and personal builds
4. Multiple VCS configurations

---

## Getting Started: Build Step Timing (Priority #1)

**Rationale:** Starting with build step timing is the most straightforward way to get familiar with both the TeamCity API and the Datadog webhook ingestion API. It's a contained feature that touches core parts of the codebase without requiring major refactoring.

---

## 1. Add Build Step Timing Information

### Goals
- Extract timing for individual build steps (not just jobs)
- Include step-level metrics in webhooks
- Maintain backward compatibility
- **Learn the TeamCity API and Datadog ingestion API**

### Current State
- Only track job-level timing (start, end, queue time)
- No step-level granularity
- Webhooks sent to: `https://webhook-intake.{ddSite}/api/v2/webhook`

### Phase 1: Research & Documentation

#### 1.1 TeamCity API Investigation
**Goal:** Understand how to access build step information and timing data

**Research Questions:**
- [ ] How to access build steps from `SBuild` object?
- [ ] Are step timings available programmatically?
- [ ] What data structure contains step information?
- [ ] Can we get step status (success/failure)?

**API Classes to Investigate:**
- [ ] `SBuild` - Check methods for build steps, statistics, messages
- [ ] **`jetbrains.buildServer.serverSide.build.steps`** ⭐ - Most promising package for build step data
- [ ] `jetbrains.buildServer.serverSide.statistics` - Build statistics
- [ ] `jetbrains.buildServer.serverSide.statistics.build` - Build-level statistics
- [ ] `jetbrains.buildServer.serverSide.buildLog` - Build log access
- [ ] `jetbrains.buildServer.messages.BuildMessage1` - Build log messages
- [ ] `jetbrains.buildServer.serverSide.SRunningBuild` - Running build information
- [ ] `jetbrains.buildServer.agent.impl.buildStages` - Agent-side build stages (for reference)

**How to Research:**
```java
// In BuildChainProcessor, add debug logging to explore available data:
SBuild build = ...;
LOG.info("Build methods: " + Arrays.toString(build.getClass().getMethods()));
LOG.info("Build statistics: " + build.getStatistics());
LOG.info("Build messages: " + build.getBuildLog());

// Look for step-related data in test builds
```

**Documentation to Review:**
- [x] **TeamCity Server API JavaDoc:** https://javadoc.jetbrains.net/teamcity/openapi/current/
  - Focus on: `jetbrains.buildServer.serverSide.build.steps` package
  - Also review: `jetbrains.buildServer.serverSide.statistics.*` packages
- [x] **TeamCity Plugin Development Guide:** https://plugins.jetbrains.com/docs/teamcity/developing-teamcity-plugins.html
- [ ] **Server-side Object Model:** https://plugins.jetbrains.com/docs/teamcity/server-side-object-model.html
- [ ] TeamCity REST API (may provide hints): https://www.jetbrains.com/help/teamcity/rest/teamcity-rest-api-documentation.html

**Open-Source Plugin Examples:**
- [ ] **Git VCS Support:** https://github.com/JetBrains/teamcity-git
- [ ] **REST API Plugin:** https://github.com/JetBrains/teamcity-rest
- [ ] **Commit Status Publisher:** https://github.com/JetBrains/commit-status-publisher

**Findings Log:**
```
Date: ___________
Researcher: ___________

[Document findings here as we investigate]

Example:
- SBuild.getBuildLog() returns: ...
- Build step data structure: ...
- Timing fields available: ...
```

#### 1.2 Datadog Webhook API Investigation
**Goal:** Understand what data Datadog's webhook intake supports for CI pipelines

**Research Questions:**
- [ ] Does Datadog support step/stage data in CI webhooks?
- [ ] What is the expected JSON schema for steps?
- [ ] Are there field limits or naming conventions?
- [ ] Do we need to send separate events or nested data?

**Resources to Review:**
- [ ] Datadog CI Visibility - Webhooks: https://docs.datadoghq.com/continuous_integration/pipelines/custom/
- [ ] Datadog Webhook Intake API: https://docs.datadoghq.com/api/latest/ci-visibility-pipelines/
- [ ] Current implementation in `DatadogClient.java` - headers, payload structure
- [ ] Existing JSON test files: `complete-pipeline.json`, `complete-job.json`

**Current Webhook Structure:**
```json
{
  "level": "job",
  "name": "Build :: Compile",
  "url": "...",
  "start": "2026-01-20T10:00:00Z",
  "end": "2026-01-20T10:02:30Z",
  "status": "success",
  "git": { ... }
}
```

**Questions:**
- Can we add a `steps` array to the job webhook?
- Should steps be separate events with `level: "step"`?
- Does Datadog have a concept of "stages" or "steps" in their CI model?

**Findings Log:**
```
Date: ___________
Researcher: ___________

[Document findings here]

Example:
- Datadog supports steps via: ...
- Expected schema: ...
- Limitations: ...
```

### Phase 2: Design

#### 2.1 Data Model Design
**Decision Point:** How to represent steps in our webhook

**Option A: Nested Steps in Job Webhook**
```json
{
  "level": "job",
  "name": "Build :: Compile",
  "steps": [
    {
      "name": "Checkout",
      "start": "2026-01-20T10:00:00Z",
      "end": "2026-01-20T10:00:15Z",
      "duration_ms": 15000,
      "status": "success"
    },
    {
      "name": "Maven compile",
      "start": "2026-01-20T10:00:15Z",
      "end": "2026-01-20T10:02:30Z",
      "duration_ms": 135000,
      "status": "success"
    }
  ]
}
```

**Pros:**
- Single webhook per job (fewer API calls)
- Clear parent-child relationship
- Backward compatible (steps field is optional)

**Cons:**
- May not match Datadog's schema expectations
- Large payloads if many steps

**Option B: Separate Step Webhooks**
```json
{
  "level": "step",
  "name": "Checkout",
  "job_id": "12345",
  "pipeline_id": "67890",
  "start": "2026-01-20T10:00:00Z",
  "end": "2026-01-20T10:00:15Z",
  "status": "success"
}
```

**Pros:**
- More granular events
- May align better with Datadog's event model

**Cons:**
- Many more API calls
- Need to ensure proper ordering/relationships

**Decision:** Choose based on Datadog API documentation findings

#### 2.2 Java Model Classes

**Create: `BuildStep.java`**
```java
package jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BuildStep {
    @JsonProperty("name")
    @Nonnull
    private final String name;
    
    @JsonProperty("start")
    @Nonnull
    private final String start;
    
    @JsonProperty("end")
    @Nonnull
    private final String end;
    
    @JsonProperty("duration_ms")
    private final long durationMs;
    
    @JsonProperty("status")
    @Nonnull
    private final StepStatus status;
    
    @JsonProperty("error")
    @Nullable
    private final String error;
    
    // Constructor, getters, toString, equals, hashCode
    
    public enum StepStatus {
        @JsonProperty("success") SUCCESS,
        @JsonProperty("error") ERROR,
        @JsonProperty("canceled") CANCELED,
        @JsonProperty("skipped") SKIPPED
    }
}
```

**Modify: `JobWebhook.java`**
```java
@JsonProperty("steps")
@Nullable
private final List<BuildStep> steps;
```

### Phase 3: Implementation

#### 3.1 Extract Build Step Information
**File:** `BuildChainProcessor.java`

**Tasks:**
- [ ] Research completed - know how to get step data from TeamCity
- [ ] Create method: `private List<BuildStep> extractBuildSteps(SBuild build)`
- [ ] Map TeamCity step status to our `StepStatus` enum
- [ ] Handle missing or incomplete step data gracefully
- [ ] Add logging for debugging

**Pseudo-code:**
```java
private List<BuildStep> extractBuildSteps(SBuild build) {
    // Based on TeamCity API research findings:
    // Option 1: build.getStatistics().getAllSteps()
    // Option 2: Parse build.getBuildLog()
    // Option 3: build.getBuildMessages() filtered by type
    
    List<BuildStep> steps = new ArrayList<>();
    
    // TODO: Implement based on API research
    
    return steps;
}
```

#### 3.2 Include Steps in Job Webhooks
**File:** `BuildChainProcessor.java` → `createJobWebhooks()` method

```java
private JobWebhook createJobWebhook(SBuild jobBuild, String pipelineId, Date pipelineStart) {
    // ... existing code ...
    
    // NEW: Extract build steps
    List<BuildStep> steps = extractBuildSteps(jobBuild);
    
    JobWebhook webhook = new JobWebhook(
        buildName(jobBuild),
        buildURL(jobBuild),
        // ... existing parameters ...
        steps.isEmpty() ? null : steps  // Only include if steps found
    );
    
    return webhook;
}
```

#### 3.3 Update Tests

**New Test File:** `BuildStepExtractionTest.java`
```java
@Test
public void shouldExtractBuildStepsWithTiming() {
    SBuild build = MockBuild.builder()
        .withSteps(/* mock step data */)
        .build();
    
    List<BuildStep> steps = processor.extractBuildSteps(build);
    
    assertThat(steps).hasSize(3);
    assertThat(steps.get(0).getName()).isEqualTo("Checkout");
    assertThat(steps.get(0).getStatus()).isEqualTo(StepStatus.SUCCESS);
}
```

**Update:** `complete-job.json` test resource
```json
{
  "level": "job",
  "name": "Build :: Compile",
  "steps": [
    {
      "name": "Checkout",
      "start": "2023-01-01T12:00:00Z",
      "end": "2023-01-01T12:00:15Z",
      "duration_ms": 15000,
      "status": "success"
    }
  ]
}
```

### Phase 4: Testing & Validation

#### 4.1 Local Testing
- [ ] Build plugin: `mvn package`
- [ ] Upload to local TeamCity instance
- [ ] Trigger test builds
- [ ] Verify step data appears in logs
- [ ] Check webhook payloads sent to Datadog

#### 4.2 Datadog Validation
- [ ] Verify webhooks are accepted by Datadog
- [ ] Check if step data appears in Datadog UI
- [ ] Validate data structure matches expectations
- [ ] Test with builds that have many steps
- [ ] Test with builds that have failed steps

### Files to Create/Modify

**New Files:**
- [ ] `datadog-ci-integration-server/src/main/java/.../model/entities/BuildStep.java`
- [ ] `datadog-ci-integration-server/src/test/java/.../BuildStepExtractionTest.java`

**Modified Files:**
- [ ] `model/entities/JobWebhook.java` - Add `steps` field
- [ ] `BuildChainProcessor.java` - Add step extraction logic
- [ ] Test resources: `complete-job.json`, `default-pipeline.json`
- [ ] `README.md` - Document step timing feature

---

## 2. Support Perforce VCS

### Goals
- Extract VCS metadata from Perforce (P4) repositories
- Maintain backward compatibility with existing Git support
- Use VCS-agnostic data model where possible

### Technical Analysis
**Current State:**
- `GitInformationExtractor` only handles `jetbrains.git` VCS
- Model (`GitInfo`) has Git-specific fields (sha, committer/author dual model)

**Perforce VCS Characteristics:**
- TeamCity VCS name: Likely `"perforce"` or `"jetbrains.perforce"`
- Uses changelists instead of commits
- Single author (no committer/author distinction)
- Different metadata structure

### Tasks

#### 2.1 Research Perforce Integration
- [ ] Investigate TeamCity's Perforce VCS root properties
- [ ] Identify Perforce VCS name constant in TeamCity API
- [ ] Map Perforce metadata to existing fields:
  - Changelist → `sha`
  - Submitter → `author_name`/`committer_name`
  - P4 depot path → `repository_url`
  - Stream/branch → `branch`

#### 2.2 Refactor VCS Extraction
- [ ] Rename `GitInformationExtractor` → `VcsInformationExtractor`
- [ ] Keep `GitInfo` model name (already VCS-agnostic to Datadog)
- [ ] Extract Git-specific logic into `extractGitMetadata()` method
- [ ] Create `extractPerforceMetadata()` method
- [ ] Update `hasGitRoot()` → `hasSupportedVcsRoot()` to check for Git OR Perforce

**Files to modify:**
- `GitInformationExtractor.java` (rename + refactor)
- `GitInformationExtractorTest.java` (rename + add Perforce tests)
- `BuildChainProcessor.java` (update references)
- `DatadogConfiguration.java` (update bean name if needed)

#### 2.3 Add Perforce Constants
```java
protected static final String GIT_VCS = "jetbrains.git";
protected static final String PERFORCE_VCS = "perforce"; // or "jetbrains.perforce"
```

#### 2.4 Update Tests
- [ ] Add Perforce test cases in `VcsInformationExtractorTest`
- [ ] Create Perforce mock builds in `MockBuild`
- [ ] Test mixed VCS scenarios

---

## 3. Support Multiple VCS Configurations

### Goals
- Handle builds with multiple VCS roots (Git + Perforce, multiple Git repos, etc.)
- Prioritize VCS root that triggered the build
- Allow manual specification via build parameters

### Technical Analysis
**Current State:**
- `build.getRevisions()` returns all VCS revisions
- Currently uses `.findFirst()` after filtering for Git
- No priority logic for multiple VCS roots

### Tasks

#### 3.1 VCS Root Selection Strategy
- [ ] **Priority 1:** VCS root that triggered the build (if VCS trigger)
- [ ] **Priority 2:** VCS root specified in build parameter `datadog.vcs.root.id`
- [ ] **Priority 3:** First supported VCS root found
- [ ] Log warning if multiple VCS roots found without clear priority

#### 3.2 Identify Triggering VCS
- [ ] Research TeamCity API for trigger information
- [ ] Check `build.getTriggeredBy()` for VCS trigger details
- [ ] Extract VCS root ID from trigger metadata

#### 3.3 Add Configuration Parameter
Add new project parameter:
- `datadog.vcs.root.id` - Specify which VCS root to use (optional)

#### 3.4 Implementation
```java
public Optional<GitInfo> extractVcsInfo(SBuild build) {
    // 1. Try to find VCS root from trigger
    Optional<BuildRevision> triggeringVcs = getVcsRootFromTrigger(build);
    if (triggeringVcs.isPresent()) {
        return extractMetadata(triggeringVcs.get());
    }
    
    // 2. Try to find VCS root from build parameter
    Optional<BuildRevision> configuredVcs = getVcsRootFromParameter(build);
    if (configuredVcs.isPresent()) {
        return extractMetadata(configuredVcs.get());
    }
    
    // 3. Fall back to first supported VCS root
    return extractMetadata(getFirstSupportedVcsRoot(build));
}
```

**Files to modify:**
- `VcsInformationExtractor.java` (add selection logic)
- `ProjectHandler.java` (document new parameter)
- `README.md` / docs (document configuration)

---

## 4. Support Manual Triggers and Personal Builds

### Goals
- Process builds regardless of trigger type (manual, VCS, schedule, etc.)
- Support personal/custom builds
- Gracefully handle missing VCS information

### Technical Analysis
**Current State:**
- `DatadogServerAdapter.isLastCompositeBuild()` filters out personal builds
- VCS extraction fails silently for manual builds (returns empty)

**Current Filters:**
```java
return build.isCompositeBuild() &&
    build.getBuildPromotion().getNumberOfDependedOnMe() == 0 &&
    !build.isPersonal();  // ❌ Blocks personal builds
```

### Tasks

#### 4.1 Remove Personal Build Filter
- [ ] Remove `!build.isPersonal()` check from `isLastCompositeBuild()`
- [ ] Add configuration option: `datadog.plugin.include.personal.builds` (default: `true`)
- [ ] Update filter logic to respect configuration

#### 4.2 Support Non-Composite Builds
Decision needed: Should we support standalone builds (not part of a chain)?

**Option A:** Only composite builds (current behavior)
- Simpler implementation
- Clear pipeline/job distinction

**Option B:** Support all builds
- Single builds → Create both pipeline and job webhook
- More comprehensive coverage
- Requires logic to handle single vs. composite builds

**Recommendation:** Start with Option A, add Option B later if needed

#### 4.3 Handle Missing VCS Information
Current behavior is already correct:
- `extractGitInfo()` returns `Optional.empty()` when no VCS
- Webhooks are sent without `git` field
- This is acceptable for manual builds

Improvements:
- [ ] Add log message indicating manual/non-VCS trigger
- [ ] Consider adding trigger metadata to webhook (if Datadog supports it)

**Files to modify:**
- `DatadogServerAdapter.java` (update filter)
- `ProjectHandler.java` (add configuration parameter)

---

## Implementation Order (Revised)

### Phase 1: Build Steps (Getting Started) ⭐
1. **Task 1.1** - Research TeamCity API for build step data
2. **Task 1.2** - Research Datadog webhook API for step support
3. **Task 2.1-2.2** - Design data model and decide on approach
4. **Task 3.1-3.2** - Implement step extraction and webhook inclusion
5. **Task 3.3** - Add comprehensive tests
6. **Task 4.1-4.2** - Local and Datadog validation

### Phase 2: Foundation (VCS Support)
7. **Task 2.2** - Refactor to `VcsInformationExtractor`
8. **Task 2.3** - Add Perforce constants and detection
9. **Task 2.1** - Research Perforce VCS integration
10. **Task 2.4** - Add Perforce tests and implementation

### Phase 3: Enhanced VCS Handling
11. **Task 3.1-3.4** - Multiple VCS root support

### Phase 4: Trigger Support
12. **Task 4.1** - Remove personal build filter
13. **Task 4.2** - Consider non-composite build support
14. **Task 4.3** - Improve logging for manual triggers

---

## API Research Links

### TeamCity API Documentation
- **Server API JavaDoc:** https://javadoc.jetbrains.net/teamcity/openapi/current/
  - **Key Packages:** `jetbrains.buildServer.serverSide.build.steps`, `jetbrains.buildServer.serverSide.statistics.*`
- **Plugin Development Guide:** https://plugins.jetbrains.com/docs/teamcity/developing-teamcity-plugins.html
- **Server-side Object Model:** https://plugins.jetbrains.com/docs/teamcity/server-side-object-model.html
- **REST API Documentation:** https://www.jetbrains.com/help/teamcity/rest/teamcity-rest-api-documentation.html

### Open-Source Plugin Examples (for reference)
- **Git VCS Support:** https://github.com/JetBrains/teamcity-git
- **REST API Plugin:** https://github.com/JetBrains/teamcity-rest (good for understanding data models)
- **Commit Status Publisher:** https://github.com/JetBrains/commit-status-publisher

### Datadog API Documentation
- **CI Visibility Overview:** https://docs.datadoghq.com/continuous_integration/
- **Custom Pipeline Integration:** https://docs.datadoghq.com/continuous_integration/pipelines/custom/
- **Webhook Intake API:** https://docs.datadoghq.com/api/latest/ci-visibility-pipelines/
- **TeamCity Integration Docs:** https://docs.datadoghq.com/continuous_integration/pipelines/teamcity/

### Current Implementation Reference
- **DatadogClient.java:** Line 32 - `WEBHOOK_INTAKE_BASE_URL = "https://webhook-intake.%s/api/v2/webhook"`
- **Test Resources:** `src/test/resources/complete-job.json`, `complete-pipeline.json`

---

## Risk & Considerations

### Breaking Changes
- Renaming `GitInformationExtractor` could break if other code depends on it
  - Mitigation: Keep as package-private, only Spring DI uses it
  
### Backward Compatibility
- All changes should be backward compatible
- Existing Git-only configurations should work unchanged
- New features are additive

### Performance Concerns
- Extracting step timing may add overhead to build processing
- Need to measure impact on TeamCity server
- Consider making step extraction optional via configuration

### Testing Requirements
- Unit tests for all new extraction logic
- Integration tests with actual TeamCity instance
- Test with builds containing many steps (100+)
- Test with different build configurations

### Documentation Updates
- README.md - Add step timing feature information
- Configuration guide - Document new parameters
- Copilot instructions - Update with new components
- JavaDoc for new classes and methods

---

## Open Questions

1. **TeamCity Step API:** Does TeamCity expose step-level timing in a structured way, or do we need to parse logs?
2. **Datadog Schema:** Does Datadog's webhook intake support nested steps or do we need separate events?
3. **Performance:** What is the overhead of extracting step data for large builds?
4. **Step Identification:** How are steps uniquely identified across build retries?
5. **Partial Failures:** If some steps succeed and others fail, how should we represent this?

---

## Success Criteria

### Phase 1 Complete When:
- [ ] Build step data can be extracted from TeamCity builds
- [ ] Step timing information is included in webhooks sent to Datadog
- [ ] Datadog successfully ingests and displays step data
- [ ] Unit tests cover step extraction logic
- [ ] Documentation updated with examples
- [ ] Code reviewed and merged

### Overall Project Complete When:
- [ ] All four enhancement areas implemented
- [ ] All tests passing (unit + integration)
- [ ] Documentation complete
- [ ] Backward compatibility verified
- [ ] Performance impact measured and acceptable
- [ ] Plugin deployed and validated with real builds

---

## Debugging & Development Setup

### Current Configuration Parameters
The plugin uses these TeamCity project parameters (in [ProjectHandler.java](datadog-ci-integration-server/src/main/java/jetbrains/buildServer/com/datadog/teamcity/plugin/ProjectHandler.java#L27-L30)):
- `datadog.ci.enabled` - Enable/disable plugin (boolean)
- `datadog.ci.api.key` - Datadog API key (set to empty string `""` to skip sending to Datadog)
- `datadog.ci.site` - Datadog site (e.g., "datadoghq.com")

### Logging Configuration

**Where Logs Go:**
- Plugin uses IntelliJ's `Logger` class: `com.intellij.openapi.diagnostic.Logger`
- Current log levels: `LOG.debug()`, `LOG.info()`, `LOG.warn()`, `LOG.error()`
- **Logs go to TeamCity server logs** (not build logs)
- Default location: `<TeamCity Data Directory>/logs/teamcity-server.log`

**Enable DEBUG Logging:**

TeamCity supports custom logging configurations per plugin. Create a file:

**File:** `<TeamCity Data Directory>/config/_logging/debug-datadog-ci-integration.xml`

See [debug-datadog-ci-integration.xml](../debug-datadog-ci-integration.xml) in the repository root for the complete configuration.

```xml
<Configuration status="INFO">
  <Appenders>
    <DelegateAppender>
      <RollingFile name="DATADOG_CI.LOG" 
                   fileName="${sys:teamcity_logs}/teamcity-datadog-ci.log" 
                   filePattern="${sys:teamcity_logs}/teamcity-datadog-ci.log.%i" 
                   append="true" createOnDemand="true">
        <PatternLayout pattern="[%d] %6p [%30.30t] - %30.30c - %m%n%ex" charset="UTF-8" />
        <SizeBasedTriggeringPolicy size="10 MB" />
        <DefaultRolloverStrategy max="10" fileIndex="min" />
      </RollingFile>
    </DelegateAppender>
  </Appenders>
  
  <Loggers>
    <Logger name="jetbrains.buildServer.com.datadog.teamcity.plugin" 
            level="DEBUG" additivity="false">
      <AppenderRef ref="DATADOG_CI.LOG" />
    </Logger>
  </Loggers>
</Configuration>
```

After adding this file:
- Restart TeamCity server OR wait ~30 seconds for auto-reload
- Plugin logs will appear in: `<TeamCity Data Directory>/logs/teamcity-datadog-ci.log`

### Required: Process All Builds Parameter

**Critical for Testing:** Add this parameter to bypass composite build filter:

```java
// Add to ProjectHandler.java
protected static final String DATADOG_PROCESS_ALL_BUILDS_PARAM = "datadog.ci.process.all.builds";

public boolean shouldProcessAllBuilds(SBuild build) {
    ProjectEx project = getProject(build);
    String processAll = project.getParameterValue(DATADOG_PROCESS_ALL_BUILDS_PARAM);
    return Boolean.parseBoolean(processAll);
}
```

Then modify `DatadogServerAdapter.onBuildFinished()` to check this parameter.

### Debug Workflow

1. **Build Plugin:** `mvn package`

2. **Install Logging Config:**
   - Copy `debug-datadog-ci-integration.xml` to `<TeamCity Data Dir>/config/_logging/`
   - Restart TeamCity OR wait for auto-reload

3. **Upload Plugin:** Administration → Plugins → Upload plugin zip

4. **Configure Test Project:** Set parameters:
   ```
   datadog.ci.enabled = true
   datadog.ci.process.all.builds = true    # Process any build, not just composite
   datadog.ci.site = datadoghq.com
   datadog.ci.api.key =                    # Empty = don't send to Datadog
   ```

5. **Run Test Build:** Trigger any build on your test configuration

6. **Check Logs:** 
   - Dedicated plugin log: `<TeamCity Data Directory>/logs/teamcity-datadog-ci.log`
   - Filter/tail: `tail -f teamcity-datadog-ci.log`

7. **Iterate:** Modify code → rebuild → re-upload plugin → test

### Remote Debugging (Advanced)

For stepping through code with a debugger:

1. Add to `<TeamCity>/bin/teamcity-server.sh`:
   ```bash
   TEAMCITY_SERVER_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
   ```

2. Restart TeamCity server

3. In IntelliJ IDEA:
   - Run → Edit Configurations → Add → Remote JVM Debug
   - Host: `localhost`, Port: `5005`
   - Click Debug

4. Set breakpoints in plugin code and trigger builds

---

## Notes & Meeting Log

### Meeting 2026-01-20
- **Decision:** Start with build step timing to familiarize with codebase
- **Action:** Research TeamCity and Datadog APIs first
- **Documentation Found:** TeamCity JavaDoc has `jetbrains.buildServer.serverSide.build.steps` package
- **Next Steps:** 
  1. Add debug configuration parameters
  2. Begin Phase 1, Task 1.1 - TeamCity API investigation
  3. Set up local TeamCity instance for testing
