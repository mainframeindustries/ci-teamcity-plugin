/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.users.SUser;

import javax.annotation.Nonnull;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildUtils {

    private static final SimpleDateFormat RFC_3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    // In TeamCity, the last composite build of the chain might start slightly after the first build of the chain.
    // This is a temporary hack to include an offset of some seconds to not incorrectly
    // filter out the first build of the chain if it started slightly before the last composite build.
    private static final int PIPELINE_START_OFFSET_MS = 3000;

    private BuildUtils() { }

    public static boolean isManualTrigger(SBuild build) {
        // Use TeamCity's own determination of whether the build was user-triggered
        // This includes: manual runs from UI, API calls, Perforce shelves, etc.
        return build.getTriggeredBy().isTriggeredByUser();
    }

    public static boolean isPartialRetry(SBuild pipelineBuild) {
        boolean isAutomaticRetry = pipelineBuild.getTriggeredBy()
                .getParameters()
                .getOrDefault("type", "").equals("retry");

        // We check if any of the jobs were started before the composite build (accounting for the offset)
        Date pipelineStartWithOffset = pipelineStartWithOffset(pipelineBuild);
        boolean isReusingBuilds = pipelineBuild.getBuildPromotion().getAllDependencies().stream()
                .filter(build -> build.getAssociatedBuild() != null)
                .anyMatch(build -> build.getAssociatedBuild().getStartDate().before(pipelineStartWithOffset));

        return isAutomaticRetry || isReusingBuilds;
    }

    public static Date pipelineStartWithOffset(SBuild pipelineBuild) {
        if (pipelineBuild.getStartDate().getTime() <= PIPELINE_START_OFFSET_MS) {
            return new Date(0);
        }

        return new Date(pipelineBuild.getStartDate().getTime() - PIPELINE_START_OFFSET_MS);
    }

    public static String buildName(SBuild build) {
        return build.getFullName();
    }

    public static long queueTimeMs(SBuild build) {
        return build.getStartDate().getTime() - build.getQueuedDate().getTime();
    }

    /**
     * Extract trigger-related tags from a build for Datadog pipeline metadata.
     * Returns a list of tags in "key:value" format that can be set on the pipeline webhook.
     */
    @Nonnull
    public static List<String> extractTriggerTags(SBuild build) {
        List<String> tags = new ArrayList<>();
        Map<String, String> triggerParams = build.getTriggeredBy().getParameters();
        
        // Extract trigger type
        String triggerType = triggerParams.get("type");
        if (triggerType != null) {
            tags.add("trigger.type:" + triggerType);
        }
        
        // Extract VCS name if it's a VCS trigger
        String vcsName = triggerParams.get("vcsName");
        if (vcsName != null) {
            tags.add("trigger.vcs:" + vcsName);
        }
        
        // Extract trigger ID
        String triggerId = triggerParams.get("triggerId");
        if (triggerId != null) {
            tags.add("trigger.trigger_id:" + triggerId);
        }
        
        // Extract user information for user-triggered builds
        if (build.getTriggeredBy().isTriggeredByUser()) {
            SUser user = build.getTriggeredBy().getUser();
            if (user != null) {
                tags.add("trigger.user:" + user.getUsername());
            } else {
                // Fallback to userId from trigger parameters if user object not available
                String userId = triggerParams.get("userId");
                if (userId != null) {
                    tags.add("trigger.user:" + userId);
                }
            }
        }
        
        // Extract Perforce shelf number for shelf-based builds
        if ("perforceShelve".equals(triggerType)) {
            String showAsIs = triggerParams.get("showAsIs");
            if (showAsIs != null) {
                // Extract shelf number from "Perforce shelf: 166770"
                Pattern shelfPattern = Pattern.compile("Perforce shelf: (\\d+)");
                Matcher matcher = shelfPattern.matcher(showAsIs);
                if (matcher.find()) {
                    tags.add("trigger.shelf:" + matcher.group(1));
                }
            }
        }
        
        // Mark personal builds
        if (build.isPersonal()) {
            tags.add("personal_build:true");
        }
        
        return tags;
    }

    public static String toRFC3339(Date date) {
        return RFC_3339.format(date);
    }
}
