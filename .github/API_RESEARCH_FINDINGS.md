# TeamCity API Research: Build Step Timing

**Date:** January 20, 2026  
**Research Goal:** Determine how to extract build step timing information from TeamCity builds

## Key Findings

### ❌ No Direct Build Step Execution API

After extensive research of the TeamCity JavaDoc, **there is no public API to directly access build step execution results** (timing, status, etc.).

**What we found:**
- `jetbrains.buildServer.serverSide.build.steps.*` - Contains step **configuration** interfaces (BuildStepsEditor, StepsFactory), not execution results
- `jetbrains.buildServer.serverSide.impl.build.steps.*` - Implementation classes for step **configuration**
- `Build.getLogMessages()` - Returns `List<String>` (plain text log lines, not structured)
- `BuildMessage1` / `BlockData` - Low-level message classes, not directly accessible from SBuild

### ✅ Potential Solution: Build Statistics

The `SBuild` interface provides:
```java
@Nullable BigDecimal getStatisticValue(String valueTypeKey)
@NotNull Map<String,BigDecimal> getStatisticValues()
```

**Documentation states:**
> Returns all statistics values associated with this build. Includes all predefined ValueTypes reported for this build and **custom metrics reported by service message during the build** as well

This suggests TeamCity **may** report step timings as custom statistics that we can query.

## Possible Approaches

### Approach 1: Statistics Values (Most Promising)
Query `build.getStatisticValues()` and look for step-related keys. TeamCity might report:
- Step duration metrics
- Custom statistics per runner

**Investigation needed:**
1. Create a test build with multiple steps
2. Call `getStatisticValues()` and log all keys
3. Identify step-specific statistic keys
4. Extract timing information from statistics

### Approach 2: Build Log Parsing (Fallback)
Parse structured log messages from `build.getBuildLog()`:
- Look for block start/end markers (e.g., "##teamcity[blockStarted]", "##teamcity[blockFinished]")
- Extract timestamps from messages
- Calculate durations manually

**Downsides:**
- Fragile (depends on log format)
- Performance-intensive (parsing entire build log)
- May miss information if logs are truncated

### Approach 3: Service Messages (Advanced)
TeamCity uses service messages for communication. Agents send messages like:
```
##teamcity[buildStatisticValue key='stepDuration:MyStep' value='15000']
```

We might be able to:
1. Access these via `getBuildLog()` or internal APIs
2. Parse service messages directly
3. Extract step timing data

**Note:** This requires reverse-engineering TeamCity's internal message format.

## Recommended Next Steps

1. **Create Debug Code:** Add logging to `BuildChainProcessor` to inspect:
   ```java
   Map<String, BigDecimal> stats = build.getStatisticValues();
   LOG.info("Build statistics keys: " + stats.keySet());
   for (Map.Entry<String, BigDecimal> entry : stats.entrySet()) {
       LOG.info("  " + entry.getKey() + " = " + entry.getValue());
   }
   ```

2. **Test with Real Build:** Run a build with multiple steps and examine the log output

3. **Identify Patterns:** Look for statistic keys that correspond to build steps

4. **Implement Extraction:** Once we find the keys, extract step timing from statistics

## Alternative: Feature Request

If no existing API supports step timing:
- This might be a missing feature in TeamCity's public API
- Consider filing a feature request with JetBrains
- Or use internal/reflection-based approach (risky for upgrades)

## Resources

- [SBuild JavaDoc](https://javadoc.jetbrains.net/teamcity/openapi/current/jetbrains/buildServer/serverSide/SBuild.html)
- [Build Statistics Documentation](https://javadoc.jetbrains.net/teamcity/openapi/current/jetbrains/buildServer/serverSide/BuildStatistics.html)
- [TeamCity Service Messages](https://www.jetbrains.com/help/teamcity/service-messages.html)

## Status

🔴 **BLOCKED:** Need to test statistics API with real build before implementing extraction logic.

**Action Required:** Deploy plugin to test TeamCity instance and log statistic values to identify step timing keys.
