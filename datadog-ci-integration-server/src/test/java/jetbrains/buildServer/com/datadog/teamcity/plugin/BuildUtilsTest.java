/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.serverSide.SRunningBuild;
import org.junit.Test;

import java.util.List;

import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.extractTriggerTags;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static org.assertj.core.api.Assertions.assertThat;

public class BuildUtilsTest {

    @Test
    public void shouldExtractUserTriggerTags() {
        // Given
        SRunningBuild build = new MockBuild.Builder(1, PIPELINE)
            .isTriggeredByUser()
            .build();

        // When
        List<String> tags = extractTriggerTags(build);

        // Then
        assertThat(tags).containsExactlyInAnyOrder(
            "trigger.type:user",
            "trigger.user:1"
        );
    }

    @Test
    public void shouldExtractScheduleTriggerTags() {
        // Given
        SRunningBuild build = new MockBuild.Builder(1, PIPELINE)
            .isTriggeredBySchedule()
            .build();

        // When
        List<String> tags = extractTriggerTags(build);

        // Then
        assertThat(tags).containsExactlyInAnyOrder(
            "trigger.type:schedule",
            "trigger.trigger_id:TRIGGER_1"
        );
    }

    @Test
    public void shouldExtractRetryTriggerTags() {
        // Given
        SRunningBuild build = new MockBuild.Builder(1, PIPELINE)
            .isTriggeredByRetry()
            .build();

        // When
        List<String> tags = extractTriggerTags(build);

        // Then
        assertThat(tags).containsExactly("trigger.type:retry");
    }

    @Test
    public void shouldExtractPersonalBuildTag() {
        // Given
        SRunningBuild build = new MockBuild.Builder(1, PIPELINE)
            .isPersonal()
            .isTriggeredByUser()
            .build();

        // When
        List<String> tags = extractTriggerTags(build);

        // Then
        assertThat(tags).contains("personal_build:true");
    }
}
