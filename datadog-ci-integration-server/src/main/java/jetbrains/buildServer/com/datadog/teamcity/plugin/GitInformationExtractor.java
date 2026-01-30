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
        
        // Try the configured VCS index first, then fallback to trying all in order
        BuildRevision revision = allRevisions.get(vcsIndex);
        VcsModificationEx vcsModification = null;
        VcsRootInstanceEx vcsRootInstance = null;
        String vcsType = null;

        // Check if the selected VCS is supported
        if (isSupportedVcs(revision)) {
            vcsRootInstance = (VcsRootInstanceEx) revision.getRoot();
            vcsType = vcsRootInstance.getVcsName();
            String revisionString = revision.getRevision();
            
            // Try to find the VCS modification
            vcsModification = (VcsModificationEx) vcsRootInstance.findModificationByVersion(revisionString);
            
            if (vcsModification == null) {
                LOG.warn(format("Could not find VCS modification for build '%s', vcsIndex: %d, VCS type: '%s', revision: '%s'",
                    build.getBuildId(), vcsIndex, vcsType, revisionString));
            }
        }
        
        // Fallback: if modification not found and there are multiple VCS entries, try them all
        if (vcsModification == null && allRevisions.size() > 1) {
            LOG.debug(format("Attempting fallback: trying all VCS entries for build '%s'", build.getBuildId()));
            
            for (int i = 0; i < allRevisions.size(); i++) {
                if (i == vcsIndex) {
                    continue; // Already tried this one
                }
                
                BuildRevision fallbackRevision = allRevisions.get(i);
                if (!isSupportedVcs(fallbackRevision)) {
                    continue;
                }
                
                VcsRootInstanceEx fallbackVcsRoot = (VcsRootInstanceEx) fallbackRevision.getRoot();
                String fallbackVcsType = fallbackVcsRoot.getVcsName();
                String fallbackRevisionString = fallbackRevision.getRevision();
                
                VcsModificationEx fallbackModification = (VcsModificationEx) fallbackVcsRoot.findModificationByVersion(fallbackRevisionString);
                
                if (fallbackModification != null) {
                    LOG.info(format("Fallback successful: using VCS index %d (type: '%s', revision: '%s') for build '%s'",
                        i, fallbackVcsType, fallbackRevisionString, build.getBuildId()));
                    vcsModification = fallbackModification;
                    vcsRootInstance = fallbackVcsRoot;
                    vcsType = fallbackVcsType;
                    break;
                }
            }
        }
        
        if (vcsModification == null) {
            LOG.warn(format("Could not find any VCS modification for build '%s' after trying all %d revisions",
                build.getBuildId(), allRevisions.size()));
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
            .withRepositoryURL(convertGitUrlToHttps(vcsRootInstance.getProperty(URL_PROPERTY)))
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

        String stream = vcsRootInstance.getProperty(STREAM_PROPERTY);
        return new GitInfo()
            .withRepositoryURL(convertP4PortToUrl(vcsRootInstance.getProperty(PORT_PROPERTY), stream))
            .withDefaultBranch(stream)
            .withMessage(vcsModification.getDescription().trim())
            .withSha(convertPerforceVersionToSha(vcsModification.getVersion()))
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
     * Converts Git SSH URL to HTTPS format for API compatibility.
     * Examples:
     *   git@github.com:owner/repo.git -> https://github.com/owner/repo.git
     *   user@gitlab.com:group/project.git -> https://gitlab.com/group/project.git
     */
    protected String convertGitUrlToHttps(String gitUrl) {
        if (gitUrl == null) {
            return null;
        }
        // Convert SSH format: user@host:path -> https://host/path
        int atIndex = gitUrl.indexOf('@');
        if (atIndex > 0) {
            int colonIndex = gitUrl.indexOf(':', atIndex);
            if (colonIndex > atIndex) {
                String host = gitUrl.substring(atIndex + 1, colonIndex);
                String path = gitUrl.substring(colonIndex + 1);
                return "https://" + host + "/" + path;
            }
        }
        // Already HTTPS or HTTP, return as-is
        return gitUrl;
    }

    /**
     * Converts Perforce P4PORT format to a proper URL scheme.
     * Examples:
     *   ssl:perforce.example.com:1666 -> https://perforce.example.com:1666/depot.git
     *   perforce.example.com:1666 -> http://perforce.example.com:1666/depot.git
     */
    protected String convertP4PortToUrl(String p4Port, String stream) {
        if (p4Port == null) {
            return null;
        }
        
        // Use full stream path (e.g., //foo/bar -> /foo/bar.git)
        String repoPath = "depot.git";
        if (stream != null && stream.startsWith("//")) {
            repoPath = stream.substring(1) + ".git";  // Remove leading / from //
        }
        
        if (p4Port.startsWith("ssl:")) {
            return "https://" + p4Port.substring(4) + repoPath;
        } else if (p4Port.startsWith("tcp:")) {
            return "http://" + p4Port.substring(4) + repoPath;
        } else {
            // Assume plain connection if no prefix
            return "http://" + p4Port + repoPath;
        }
    }

    /**
     * Converts Perforce version to a Git-compatible SHA-1 hash.
     * Extracts the changelist number from Perforce version (e.g., //project/main|12345)
     * and pads it with leading zeros to create a 40-character SHA (decimal digits are valid hex).
     */
    protected String convertPerforceVersionToSha(String perforceVersion) {
        if (perforceVersion == null) {
            return null;
        }
        // Extract changelist number after the pipe character
        int pipeIndex = perforceVersion.lastIndexOf('|');
        String changelist = (pipeIndex >= 0 && pipeIndex < perforceVersion.length() - 1) 
            ? perforceVersion.substring(pipeIndex + 1)
            : perforceVersion;
        
        // Pad with leading zeros to make a 40-character SHA
        return String.format("%040d", Long.parseLong(changelist));
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

