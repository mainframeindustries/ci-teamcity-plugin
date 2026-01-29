/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.DEFAULT_EMAIL_DOMAIN;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.GIT_VCS;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.PERFORCE_VCS;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.EMAIL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.FULL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.NAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.GitInformationExtractor.UsernameStyle.USERID;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.MockBuild.BuildType.PIPELINE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_AUTHOR_USERNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_BRANCH;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_DATE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMIT_SHA;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_COMMITTER_USERNAME;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_GIT_MESSAGE;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.DEFAULT_REPO_URL;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.TestUtils.EMPTY_AUTHOR_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GitInformationExtractorTest {

    private ProjectHandler projectHandler;
    private GitInformationExtractor gitInfoExtractor;

    @Before
    public void setUp() {
        projectHandler = mock(ProjectHandler.class);
        when(projectHandler.getEmailPostfix(any(SBuild.class))).thenReturn("@teamcity");
        when(projectHandler.getVcsIndex(any(SBuild.class))).thenReturn(0);
        gitInfoExtractor = new GitInformationExtractor(projectHandler);
    }

    @Test
    public void shouldReturnEmptyIfNoRevisionIsFound() {
        SBuild build = new MockBuild.Builder(1, PIPELINE).build();

        Optional<GitInfo> gitInfo = gitInfoExtractor.extractGitInfo(build);

        assertThat(gitInfo).isEmpty();
    }

    @Test
    public void shouldReturnEmptyForNonGitRevisions() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision("NOT-GIT", "", DEFAULT_COMMITTER_USERNAME, DEFAULT_AUTHOR_USERNAME)
            .build();

        Optional<GitInfo> gitInfo = gitInfoExtractor.extractGitInfo(build);

        assertThat(gitInfo).isEmpty();
    }

    @Test
    public void shouldParseCorrectFullStyleUsername() {
        // Setup
        String committerUsername = "John Doe <johndoe@datadog.com>";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), committerUsername, EMPTY_AUTHOR_USERNAME)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("John Doe")
            .withCommitterName("John Doe")
            .withAuthorEmail("johndoe@datadog.com")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForInvalidFullStyleUsername() {
        // Setup: username is missing the "<email>" part
        String invalidFullUsername = "Invalid Full Username";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), invalidFullUsername, EMPTY_AUTHOR_USERNAME)
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test
    public void shouldParseCorrectEmailUsername() {
        // Setup
        String committerUsername = "johndoe@datadog.com";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, EMAIL.name(), committerUsername, EMPTY_AUTHOR_USERNAME)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("johndoe")
            .withCommitterName("johndoe")
            .withAuthorEmail("johndoe@datadog.com")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldCreateDefaultEmailForNameStyle() {
        // Setup
        String committerUsername = "John Doe";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, NAME.name(), committerUsername, EMPTY_AUTHOR_USERNAME)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("John Doe")
            .withCommitterName("John Doe")
            .withAuthorEmail("johndoe@teamcity")
            .withCommitterEmail("johndoe@teamcity");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldCreateDefaultEmailForUserIDStyle() {
        // Setup
        String committerUsername = "johndoe";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, USERID.name(), committerUsername, EMPTY_AUTHOR_USERNAME)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("johndoe")
            .withCommitterName("johndoe")
            .withAuthorEmail("johndoe@teamcity")
            .withCommitterEmail("johndoe@teamcity");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldUseAuthorInformationIfPresent() {
        // Setup
        String committerUsername = "John Doe <johndoe@datadog.com>";
        String authorUsername = "Jane Doe <janedoe@datadog.com>";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), committerUsername, authorUsername)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withAuthorName("Jane Doe")
            .withAuthorEmail("janedoe@datadog.com")
            .withCommitterName("John Doe")
            .withCommitterEmail("johndoe@datadog.com");

        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfUsernameStyleIsEmpty() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, "", DEFAULT_COMMITTER_USERNAME, EMPTY_AUTHOR_USERNAME)
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfUsernameStyleIsNull() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, null, DEFAULT_COMMITTER_USERNAME, EMPTY_AUTHOR_USERNAME)
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionForInvalidUsernameStyle() {
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, "INVALID", "John Doe <johndoe@datadog.com>", EMPTY_AUTHOR_USERNAME)
            .build();

        gitInfoExtractor.extractGitInfo(build);
    }

    @Test
    public void shouldExtractPerforceInformation() {
        // Setup: Perforce build with simple username
        String perforcePort = "ssl:perforce.example.com:1666";
        String perforceStream = "//project/main";
        String perforceVersion = "//project/main|12345";
        String perforceCommitter = "build";
        String perforceMessage = "Fixed bug in rendering system";
        
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addPerforceRevision(perforcePort, perforceStream, perforceVersion, perforceCommitter, perforceMessage)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = new GitInfo()
            .withRepositoryURL("https://perforce.example.com:1666/project/main.git")
            .withDefaultBranch(perforceStream)
            .withSha("0000000000000000000000000000000000012345")
            .withMessage(perforceMessage)
            .withCommitterName(perforceCommitter)
            .withCommitterEmail("build@teamcity")
            .withAuthorName(perforceCommitter)
            .withAuthorEmail("build@teamcity")
            .withBranch(DEFAULT_BRANCH)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE));
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldGenerateEmailForPerforceUsername() {
        // Setup: Perforce with username that has spaces
        String perforcePort = "ssl:perforce.example.com:1666";
        String perforceStream = "//project/main";
        String perforceVersion = "//tasks/feature_branch|67890";
        String perforceCommitter = "John Doe";
        String perforceMessage = "Added new feature";
        
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addPerforceRevision(perforcePort, perforceStream, perforceVersion, perforceCommitter, perforceMessage)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = new GitInfo()
            .withRepositoryURL("https://perforce.example.com:1666/project/main.git")
            .withDefaultBranch(perforceStream)
            .withSha("0000000000000000000000000000000000067890")
            .withMessage(perforceMessage)
            .withCommitterName("John Doe")
            .withCommitterEmail("johndoe@teamcity")
            .withAuthorName("John Doe")
            .withAuthorEmail("johndoe@teamcity")
            .withBranch(DEFAULT_BRANCH)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE));
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldUseCustomEmailPostfixForPerforce() {
        // Setup: Configure custom email postfix
        String customEmailPostfix = "@foo.com";
        when(projectHandler.getEmailPostfix(any(SBuild.class))).thenReturn(customEmailPostfix);
        
        String perforcePort = "ssl:perforce.example.com:1666";
        String perforceStream = "//project/main";
        String perforceVersion = "//project/main|12345";
        String perforceCommitter = "gabe";
        String perforceMessage = "Updated configuration";
        
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addPerforceRevision(perforcePort, perforceStream, perforceVersion, perforceCommitter, perforceMessage)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then - should use custom email postfix
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = new GitInfo()
            .withRepositoryURL("https://perforce.example.com:1666/project/main.git")
            .withDefaultBranch(perforceStream)
            .withSha("0000000000000000000000000000000000012345")
            .withMessage(perforceMessage)
            .withCommitterName(perforceCommitter)
            .withCommitterEmail("gabe@foo.com")
            .withAuthorName(perforceCommitter)
            .withAuthorEmail("gabe@foo.com")
            .withBranch(DEFAULT_BRANCH)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE));
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldPreferFirstSupportedVcsInHybridBuild() {
        // Setup: Build with both Git and Perforce (hybrid)
        String gitCommitter = "git-user <git@example.com>";
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), gitCommitter, EMPTY_AUTHOR_USERNAME)
            .addPerforceRevision("ssl:perforce.example.com:1666", "//project/main", 
                               "//project/main|12345", "p4-user", "Perforce change")
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then - should use first supported VCS (Git)
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = defaultGitInfo()
            .withCommitterName("git-user")
            .withCommitterEmail("git@example.com")
            .withAuthorName("git-user")
            .withAuthorEmail("git@example.com");
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldUseVcsIndexToSelectRevision() {
        // Setup: Build with both Git and Perforce, configured to use index 1 (Perforce)
        when(projectHandler.getVcsIndex(any(SBuild.class))).thenReturn(1);
        
        String gitCommitter = "git-user <git@example.com>";
        String perforcePort = "ssl:perforce.example.com:1666";
        String perforceStream = "//project/main";
        String perforceVersion = "//project/main|12345";
        String perforceCommitter = "p4-user";
        String perforceMessage = "Perforce change";
        
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), gitCommitter, EMPTY_AUTHOR_USERNAME)
            .addPerforceRevision(perforcePort, perforceStream, perforceVersion, perforceCommitter, perforceMessage)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then - should use VCS at index 1 (Perforce)
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = new GitInfo()
            .withRepositoryURL("https://perforce.example.com:1666/project/main.git")
            .withDefaultBranch(perforceStream)
            .withSha("0000000000000000000000000000000000012345")
            .withMessage(perforceMessage)
            .withCommitterName(perforceCommitter)
            .withCommitterEmail("p4-user@teamcity")
            .withAuthorName(perforceCommitter)
            .withAuthorEmail("p4-user@teamcity")
            .withBranch(DEFAULT_BRANCH)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE));
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldUseNegativeIndexToSelectFromEnd() {
        // Setup: Build with both Git and Perforce, configured to use index -1 (last = Perforce)
        when(projectHandler.getVcsIndex(any(SBuild.class))).thenReturn(-1);
        
        String gitCommitter = "git-user <git@example.com>";
        String perforcePort = "ssl:perforce.example.com:1666";
        String perforceStream = "//project/main";
        String perforceVersion = "//project/main|12345";
        String perforceCommitter = "p4-user";
        String perforceMessage = "Perforce change";
        
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), gitCommitter, EMPTY_AUTHOR_USERNAME)
            .addPerforceRevision(perforcePort, perforceStream, perforceVersion, perforceCommitter, perforceMessage)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then - should use last VCS (Perforce)
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = new GitInfo()
            .withRepositoryURL("https://perforce.example.com:1666/project/main.git")
            .withDefaultBranch(perforceStream)
            .withSha("0000000000000000000000000000000000012345")
            .withMessage(perforceMessage)
            .withCommitterName(perforceCommitter)
            .withCommitterEmail("p4-user@teamcity")
            .withAuthorName(perforceCommitter)
            .withAuthorEmail("p4-user@teamcity")
            .withBranch(DEFAULT_BRANCH)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE));
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    @Test
    public void shouldClampIndexToValidRange() {
        // Setup: Build with Git and Perforce, configured to use index 100 (out of bounds, should clamp to 1)
        when(projectHandler.getVcsIndex(any(SBuild.class))).thenReturn(100);
        
        String gitCommitter = "git-user <git@example.com>";
        String perforcePort = "ssl:perforce.example.com:1666";
        String perforceStream = "//project/main";
        String perforceVersion = "//project/main|12345";
        String perforceCommitter = "p4-user";
        String perforceMessage = "Perforce change";
        
        SBuild build = new MockBuild.Builder(1, PIPELINE)
            .addRevision(GIT_VCS, FULL.name(), gitCommitter, EMPTY_AUTHOR_USERNAME)
            .addPerforceRevision(perforcePort, perforceStream, perforceVersion, perforceCommitter, perforceMessage)
            .build();

        // When
        Optional<GitInfo> gitInfoOptional = gitInfoExtractor.extractGitInfo(build);

        // Then - should use last valid index (1 = Perforce)
        assertThat(gitInfoOptional).isNotEmpty();
        GitInfo expectedGitInfo = new GitInfo()
            .withRepositoryURL("https://perforce.example.com:1666/project/main.git")
            .withDefaultBranch(perforceStream)
            .withSha("0000000000000000000000000000000000012345")
            .withMessage(perforceMessage)
            .withCommitterName(perforceCommitter)
            .withCommitterEmail("p4-user@teamcity")
            .withAuthorName(perforceCommitter)
            .withAuthorEmail("p4-user@teamcity")
            .withBranch(DEFAULT_BRANCH)
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE));
        
        assertThat(gitInfoOptional.get()).isEqualTo(expectedGitInfo);
    }

    private static GitInfo defaultGitInfo() {
        return new GitInfo()
            .withSha(DEFAULT_COMMIT_SHA)
            .withAuthorTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withCommitTime(toRFC3339(DEFAULT_COMMIT_DATE))
            .withBranch(DEFAULT_BRANCH)
            .withDefaultBranch(DEFAULT_BRANCH)
            .withRepositoryURL(DEFAULT_REPO_URL)
            .withMessage(DEFAULT_GIT_MESSAGE);
    }
}
