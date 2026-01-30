# Datadog CI TeamCity Integration

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A TeamCity Plugin that provides the integration with the [Datadog CI Visibility](https://www.datadoghq.com/product/ci-cd-monitoring/) product.

## Fork Notice

This is a fork of the [official Datadog TeamCity plugin](https://github.com/DataDog/ci-teamcity-plugin) with additional features and enhancements.

### Additional Features

This fork includes the following enhancements over the official plugin:

- **Batch Webhook Sending**: Send multiple CI events in configurable batches to reduce API calls and improve performance
- **Trigger-Based Chain Membership**: Improved build chain detection using TeamCity's TriggeredBy API with support for diamond dependencies
- **Build Step Timing**: Extract and report individual build step timing information as separate webhook events
- **Manual Build Detection**: Automatically detect and tag manually triggered builds
- **Trigger Information Tags**: Extract and report trigger information as pipeline tags
- **Non-Composite Build Support**: Allow build chains that are not terminated by a composite build (removes requirement for composite build at top of chain)
- **Personal Build Support**: Option to enable CI visibility for personal builds
- **Perforce Support**: Support for Perforce repositories by converting them to Git-compatible format for Datadog CI Visibility (P4PORT URLs converted to https://, changelists treated as commit SHAs)
- **SSH Git URL Conversion**: Automatically converts SSH-style Git URLs (e.g., `git@github.com:owner/repo.git`) to HTTPS format for proper ingestion by Datadog
- **Custom Email Postfix**: Configurable email domain suffix for user attribution
- **VCS Entry Selection**: Select which VCS root to use when multiple are present
- **Job-Level Parameter Overrides**: Override configuration parameters at the job level
- **Enhanced Logging**: Improved debugging and logging infrastructure
- **Java 9+ Compatibility**: Updated dependencies (Mockito 3.12.4) for modern Java versions
- **Millisecond Precision**: RFC3339 timestamps now include millisecond precision

## Configuration Parameters

All configuration parameters are set as TeamCity build parameters with the `datadog.ci.` prefix:

| Parameter | Default | Required | Description |
|-----------|---------|----------|-------------|
| `datadog.ci.api.key` | - | **Yes** | Your Datadog API key for sending CI visibility data |
| `datadog.ci.site` | - | **Yes** | Your Datadog site (e.g., `datadoghq.com`, `datadoghq.eu`, `us5.datadoghq.com`) |
| `datadog.ci.enabled` | `false` | No | Enable/disable the Datadog CI integration |
| `datadog.ci.batch.size` | `20` | No | Number of webhooks to send in each batch (must be > 0) |
| `datadog.ci.enable.non-composite` | `false` | No | Allow build chains that are not terminated by a composite build (removes requirement for composite build at top of chain) |
| `datadog.ci.enable.personal` | `false` | No | Enable CI visibility for personal builds |
| `datadog.ci.email.postfix` | `@teamcity` | No | Email domain suffix appended to usernames for user attribution |
| `datadog.ci.vcs.index` | `0` | No | Index of the VCS root to use when multiple VCS entries are present |

**Note**: Parameters can be set at the project level (affecting all builds in the project) or overridden at the job level for specific build configurations.

# Build

Execute `mvn package` from the project root to build the plugin. The resulting datadog-ci-integration.zip file will be
generated in the 'target' directory.

```
mvn package
```

# Usage

The plugin needs to be configured before it can be used. Please refer to the [TeamCity Setup](https://docs.datadoghq.com/continuous_integration/pipelines/teamcity/) for the Datadog CI Visibility product.

