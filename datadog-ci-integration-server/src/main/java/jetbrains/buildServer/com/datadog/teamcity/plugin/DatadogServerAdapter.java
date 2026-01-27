/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.util.EventDispatcher;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.Map;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.buildName;

@Component
public class DatadogServerAdapter extends BuildServerAdapter {

    private static final Logger LOG = Logger.getInstance(DatadogServerAdapter.class.getName());

    private final BuildsManager buildsManager;
    private final BuildChainProcessor buildChainProcessor;
    private final ProjectHandler projectHandler;

    public DatadogServerAdapter(EventDispatcher<BuildServerListener> eventListener,
                                BuildsManager buildsManager,
                                BuildChainProcessor buildChainProcessor,
                                ProjectHandler projectHandler) {
        this.buildsManager = buildsManager;
        this.buildChainProcessor = buildChainProcessor;
        this.projectHandler = projectHandler;

        eventListener.addListener(this);
    }

    @Override
    public void buildFinished(@Nonnull SRunningBuild build) {
        onBuildFinished(build);
    }

    @Override
    public void buildInterrupted(SRunningBuild build) {
        onBuildFinished(build);
    }

    private void onBuildFinished(SRunningBuild build) {
        if (!projectHandler.isPluginEnabled(build)) {
            return;
        }

        String ignoreReason = getIgnoreReason(build);
        if (ignoreReason != null) {
            LOG.info(format("Ignoring build '%s' (id: %s): %s", buildName(build), build.getBuildId(), ignoreReason));
            return;
        }

        // At this point, we know it's a build we should process
        SBuild pipelineBuild = buildsManager.findBuildInstanceById(build.getBuildId());
        if (pipelineBuild == null) {
            // This should not happen, but better to check for it anyway
            LOG.error("The TeamCity server could not find build with ID: " + build.getBuildId());
            return;
        }

        buildChainProcessor.process(pipelineBuild);
    }

    /**
     * Returns a reason why the build should be ignored, or null if it should be processed.
     */
    private String getIgnoreReason(SBuild build) {
        // Personal builds are only processed if explicitly enabled
        if (build.isPersonal() && !projectHandler.isPersonalEnabled(build)) {
            return "personal build and feature not enabled";
        }

        // Builds triggered by snapshot dependency should be processed as part of the parent chain
        if (isTriggeredBySnapshotDependency(build)) {
            return "triggered by snapshot dependency (will be processed as part of parent chain)";
        }

        // Must be the final build in the chain (no dependents)
        if (build.getBuildPromotion().getNumberOfDependedOnMe() != 0) {
            return "not a final build in chain (has dependents)";
        }

        // If it's a non-composite build, reject if feature is not enabled
        if (!build.isCompositeBuild() && !projectHandler.isNonCompositeEnabled(build)) {
            return "non-composite build and feature not enabled";
        }

        return null;
    }

    /**
     * Check if this build was triggered by a snapshot dependency.
     * If so, it should be processed as part of the parent chain, not as a standalone pipeline.
     */
    private boolean isTriggeredBySnapshotDependency(SBuild build) {
        jetbrains.buildServer.serverSide.TriggeredBy triggeredBy = build.getTriggeredBy();
        if (triggeredBy == null) {
            return false;
        }
        
        Map<String, String> params = triggeredBy.getParameters();
        return params.containsKey("type") && "snapshotDependency".equals(params.get("type"));
    }
}
