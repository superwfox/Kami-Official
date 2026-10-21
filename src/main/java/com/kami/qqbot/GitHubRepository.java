package com.kami.qqbot;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;

/** A snapshot of one repository, with the README and checks pinned to the same commit. */
public final class GitHubRepository {
    public enum BuildState { SUCCESS, FAILURE, NONE }

    public final String owner, name, description, branch, sha, readme, readmePath, buildLabel;
    public final long stars;
    public final boolean archived;
    public final Instant committedAt, fetchedAt;
    public final List<Contributor> contributors;
    public final BuildState buildState;

    public GitHubRepository(String owner, String name, String description, String branch, String sha,
                            Instant committedAt, long stars, boolean archived, List<Contributor> contributors,
                            String readme, String readmePath, BuildState buildState, String buildLabel,
                            Instant fetchedAt) {
        this.owner = owner; this.name = name; this.description = description; this.branch = branch;
        this.sha = sha; this.committedAt = committedAt; this.stars = stars; this.archived = archived;
        this.contributors = List.copyOf(contributors); this.readme = readme; this.readmePath = readmePath;
        this.buildState = buildState; this.buildLabel = buildLabel; this.fetchedAt = fetchedAt;
    }

    public String fullName() { return owner + "/" + name; }
    public String url() { return "https://github.com/" + fullName(); }

    public static final class Contributor {
        public final String login;
        public final BufferedImage avatar;
        public Contributor(String login, BufferedImage avatar) { this.login = login; this.avatar = avatar; }
    }
}
