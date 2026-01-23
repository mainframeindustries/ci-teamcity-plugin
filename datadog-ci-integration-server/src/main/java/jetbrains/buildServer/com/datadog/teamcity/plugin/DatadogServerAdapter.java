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
        // Personal builds are never processed
        if (build.isPersonal()) {
            return "personal build";
        }

        // Must be the final build in the chain (no dependents)
        if (build.getBuildPromotion().getNumberOfDependedOnMe() != 0) {
            return "not a final build in chain (has dependents)";
        }

        // If it's a composite build, always process
        if (build.isCompositeBuild()) {
            return null;
        }

        // For non-composite builds, check if the feature is enabled
        if (projectHandler.isNonCompositeEnabled(build)) {
            return null;
        }
        
        return "non-composite build and feature not enabled";
    }
}
