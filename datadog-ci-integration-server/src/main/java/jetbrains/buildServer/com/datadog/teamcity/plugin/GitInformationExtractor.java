/**
 * Unless explicitly stated otherwise all files in this repository are licensed
 * under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/)
 * Copyright 2022-present Datadog, Inc.
 */

package jetbrains.buildServer.com.datadog.teamcity.plugin;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.com.datadog.teamcity.plugin.model.entities.GitInfo;
import jetbrains.buildServer.serverSide.BuildRevision;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.vcs.VcsRootInstance;
import jetbrains.buildServer.vcs.VcsRootInstanceEx;
import jetbrains.buildServer.vcs.impl.VcsModificationEx;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static jetbrains.buildServer.com.datadog.teamcity.plugin.BuildUtils.toRFC3339;

@Component
public class GitInformationExtractor {

    protected static final String USERNAME_STYLE_PROPERTY = "usernameStyle";
    protected static final String URL_PROPERTY = "url";
    protected static final String BRANCH_PROPERTY = "branch";
    protected static final String PORT_PROPERTY = "port";
    protected static final String STREAM_PROPERTY = "stream";
    protected static final String GIT_VCS = "jetbrains.git";
    protected static final String PERFORCE_VCS = "perforce";
    protected static final String DEFAULT_EMAIL_DOMAIN = "TeamCity";
    
    protected static final Set<String> SUPPORTED_VCS_TYPES = new HashSet<>(Arrays.asList(
        GIT_VCS,
        PERFORCE_VCS
    ));

    private static final Logger LOG = Logger.getInstance(GitInformationExtractor.class.getName());
    private final ProjectHandler projectHandler;

    public GitInformationExtractor(ProjectHandler projectHandler) {
        this.projectHandler = projectHandler;
    }

    public Optional<GitInfo> extractGitInfo(SBuild build) {
        int vcsIndex = projectHandler.getVcsIndex(build);
        List<BuildRevision> allRevisions = build.getRevisions();
        
        if (allRevisions.isEmpty()) {
            LOG.warn(format("Could not find any revisions for build '%s'", build));
            return Optional.empty();
        }
        
        LOG.debug(format("Build '%s' has %d revisions, configured vcsIndex=%d", 
            build.getBuildId(), allRevisions.size(), vcsIndex));
        
        // Log all revisions for debugging
        for (int i = 0; i < allRevisions.size(); i++) {
            BuildRevision rev = allRevisions.get(i);
            LOG.debug(format("  Revision[%d]: VCS='%s', VCSRootId=%d, VCSRootName='%s', revision='%s', displayName='%s'",
                i, rev.getRoot().getVcsName(), rev.getRoot().getId(), rev.getRoot().getName(),
                rev.getRevision(), rev.getRevisionDisplayName()));
        }
        
        // Handle negative indices (Python-style: -1 = last, -2 = second-to-last, etc.)
        if (vcsIndex < 0) {
            vcsIndex = allRevisions.size() + vcsIndex;
        }
        
        // Clamp index to valid range [0, size-1]
        if (vcsIndex < 0) {
            vcsIndex = 0;
        } else if (vcsIndex >= allRevisions.size()) {
            vcsIndex = allRevisions.size() - 1;
        }
        
        LOG.debug(format("Selected vcsIndex=%d for build '%s'", vcsIndex, build.getBuildId()));
        
        BuildRevision revision = allRevisions.get(vcsIndex);
        
        // Check if the selected VCS is supported
        if (!isSupportedVcs(revision)) {
            LOG.warn(format("VCS at index %d is not supported (type: '%s') for build '%s'", 
                vcsIndex, revision.getRoot().getVcsName(), build.getBuildId()));
            return Optional.empty();
        }

        VcsRootInstanceEx vcsRootInstance = (VcsRootInstanceEx) revision.getRoot();
        String vcsType = vcsRootInstance.getVcsName();
        String revisionString = revision.getRevision();
        
        // Try to find the VCS modification
        VcsModificationEx vcsModification = (VcsModificationEx) vcsRootInstance.findModificationByVersion(revisionString);

        if (vcsModification == null) {
            LOG.warn(format("Could not find VCS modification for build '%s', vcsIndex: %d, VCS type: '%s', revision: '%s'",
                build.getBuildId(), vcsIndex, vcsType, revisionString));
            return Optional.empty();
        }

        GitInfo gitInfo;
        
        if (GIT_VCS.equalsIgnoreCase(vcsType)) {
            gitInfo = extractFromGit(vcsRootInstance, vcsModification, build);
        } else if (PERFORCE_VCS.equalsIgnoreCase(vcsType)) {
            gitInfo = extractFromPerforce(vcsRootInstance, vcsModification, build);
        } else {
            LOG.warn(format("Unsupported VCS type '%s' for build '%s'", vcsType, build.getBuildId()));
            return Optional.empty();
        }

        return Optional.of(gitInfo);
    }

    private GitInfo extractFromGit(VcsRootInstanceEx vcsRootInstance, VcsModificationEx vcsModification, SBuild build) {
        UsernameStyle usernameStyle = getUsernameStyleForGit(vcsRootInstance);
        String emailPostfix = projectHandler.getEmailPostfix(build);
        GitUserInfo committerInfo = extractCommitterInfo(vcsModification, usernameStyle, emailPostfix);
        GitUserInfo authorInfo = tryExtractAuthorInfo(vcsModification, usernameStyle, emailPostfix)
            .orElse(committerInfo);

        return new GitInfo()
            .withRepositoryURL(vcsRootInstance.getProperty(URL_PROPERTY))
            .withDefaultBranch(vcsRootInstance.getProperty(BRANCH_PROPERTY))
            .withMessage(vcsModification.getDescription().trim())
            .withSha(vcsModification.getVersion())
            .withCommitTime(toRFC3339(vcsModification.getVcsDate()))
            .withCommitterName(committerInfo.username)
            .withCommitterEmail(committerInfo.email)
            .withAuthorTime(toRFC3339(vcsModification.getVcsDate()))
            .withAuthorName(authorInfo.username)
            .withAuthorEmail(authorInfo.email)
            .withBranch(getBranch(build));
    }

    private GitInfo extractFromPerforce(VcsRootInstanceEx vcsRootInstance, VcsModificationEx vcsModification, SBuild build) {
        // Perforce uses NAME style for username (no email formatting)
        UsernameStyle usernameStyle = UsernameStyle.NAME;
        String emailPostfix = projectHandler.getEmailPostfix(build);
        GitUserInfo committerInfo = extractCommitterInfo(vcsModification, usernameStyle, emailPostfix);
        GitUserInfo authorInfo = tryExtractAuthorInfo(vcsModification, usernameStyle, emailPostfix)
            .orElse(committerInfo);

        return new GitInfo()
            .withRepositoryURL(convertP4PortToUrl(vcsRootInstance.getProperty(PORT_PROPERTY)))
            .withDefaultBranch(vcsRootInstance.getProperty(STREAM_PROPERTY))
            .withMessage(vcsModification.getDescription().trim())
            .withSha(vcsModification.getVersion())
            .withCommitTime(toRFC3339(vcsModification.getVcsDate()))
            .withCommitterName(committerInfo.username)
            .withCommitterEmail(committerInfo.email)
            .withAuthorTime(toRFC3339(vcsModification.getVcsDate()))
            .withAuthorName(authorInfo.username)
            .withAuthorEmail(authorInfo.email)
            .withBranch(getBranch(build));
    }

    private boolean isSupportedVcs(BuildRevision rev) {
        String vcsName = rev.getRoot().getVcsName();
        return SUPPORTED_VCS_TYPES.stream()
            .anyMatch(supportedVcs -> supportedVcs.equalsIgnoreCase(vcsName));
    }

    private UsernameStyle getUsernameStyleForGit(VcsRootInstanceEx vcsRootInstance) {
        String usernameStyle = vcsRootInstance.getProperty(USERNAME_STYLE_PROPERTY);
        if (usernameStyle == null || usernameStyle.isEmpty()) {
            throw new IllegalArgumentException("Could not retrieve username style from VCS root properties: " + vcsRootInstance.getProperties());
        }

        return UsernameStyle.valueOf(usernameStyle);
    }

    private GitUserInfo extractCommitterInfo(VcsModificationEx change, UsernameStyle usernameStyle, String emailPostfix) {
        String committerUsername = change.getCommiterName();
        return parseUsername(committerUsername, usernameStyle, emailPostfix);
    }

    private Optional<GitUserInfo> tryExtractAuthorInfo(SVcsModification change, UsernameStyle usernameStyle, String emailPostfix) {
        String authorUsername = change.getUserName();
        if (authorUsername == null || authorUsername.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(parseUsername(authorUsername, usernameStyle, emailPostfix));
    }

    @Nonnull
    private GitUserInfo parseUsername(String username, UsernameStyle usernameStyle, String emailPostfix) {
        switch (usernameStyle) {
            case FULL:
                return parseFullStyle(username);
            case EMAIL:
                return parseEmailStyle(username);
            case NAME:
            case USERID:
                // These styles do not have any email information, so we will generate one
                return parseStylesWithoutEmail(username, emailPostfix);
            default:
                throw new IllegalArgumentException("Cannot recognize username style: " + usernameStyle);
        }
    }

    @Nonnull
    private GitUserInfo parseFullStyle(String username) {
        // Full style has name and email (example: 'John Doe <johndoe@gmail.com>')
        int emailStartIdx = username.indexOf("<");
        if (emailStartIdx == -1) {
            throw new IllegalArgumentException("Could not find email start for username " + username);
        }

        int emailEndIdx = username.lastIndexOf(">");
        if (emailEndIdx <= emailStartIdx) {
            throw new IllegalArgumentException("Could not find email end for username " + username);
        }

        String committerUsername = username.substring(0, emailStartIdx).trim();
        String committerEmail = username.substring(emailStartIdx + 1, emailEndIdx);
        return new GitUserInfo(committerUsername, committerEmail);
    }

    @Nonnull
    private static GitUserInfo parseEmailStyle(String email) {
        // Email style has only email (example: 'johndoe@gmail.com')
        int usernameEndIndex = email.indexOf("@");
        return usernameEndIndex == -1 ?
            new GitUserInfo(email, email) :
            new GitUserInfo(email.substring(0, usernameEndIndex), email);
    }

    @Nonnull
    private GitUserInfo parseStylesWithoutEmail(String username, String emailPostfix) {
        // In these cases we generate an email for the user by adding the configured postfix
        String emailUsername = username.replaceAll("\\s", "").toLowerCase();
        return new GitUserInfo(username, emailUsername + emailPostfix);
    }

    private String getBranch(SBuild build) {
        return build.getBranch() == null ? "" : build.getBranch().getDisplayName();
    }

    private static class GitUserInfo {
        private final String username;
        private final String email;

        private GitUserInfo(String username, String email) {
            this.username = username;
            this.email = email;
        }
    }

    /**
     * Converts Perforce P4PORT format to a proper URL scheme.
     * Examples:
     *   ssl:perforce.example.com:1666 -> https://perforce.example.com:1666
     *   perforce.example.com:1666 -> http://perforce.example.com:1666
     */
    protected String convertP4PortToUrl(String p4Port) {
        if (p4Port == null) {
            return null;
        }
        if (p4Port.startsWith("ssl:")) {
            return "https://" + p4Port.substring(4);
        } else if (p4Port.startsWith("tcp:")) {
            return "http://" + p4Port.substring(4);
        } else {
            // Assume plain connection if no prefix
            return "http://" + p4Port;
        }
    }

    protected enum UsernameStyle {
        /**
         * Name (John Doe)
         */
        NAME,

        /**
         * User id based on email (johndoe)
         */
        USERID,

        /**
         * Email (johndoe@gmail.com)
         */
        EMAIL,

        /**
         * Name and Email (John Doe &lt;johndoe@gmail.com&gt;)
         */
        FULL
    }
}

