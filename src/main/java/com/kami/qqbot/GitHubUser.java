package com.kami.qqbot;

import java.awt.image.BufferedImage;
import java.time.Instant;

/** Public profile fields, plus the same-name README repository when GitHub actually has one. */
public final class GitHubUser {
    /** Shown in the README area when login/login does not exist. */
    public static final String MISSING_PROFILE_README = "该用户当前还没有这个特殊仓库，可能还是一个萌萌哒新人";

    public final String login, name, bio, company, location, blog;
    public final long publicRepos, publicGists, followers, following;
    public final Instant createdAt, fetchedAt;
    public final BufferedImage avatar;
    /** Null when the same-name repository returns 404. Private repositories are never stored here. */
    public final GitHubRepository profileRepository;

    public GitHubUser(String login, String name, String bio, String company, String location, String blog,
                      long publicRepos, long publicGists, long followers, long following,
                      Instant createdAt, Instant fetchedAt, BufferedImage avatar,
                      GitHubRepository profileRepository) {
        this.login = login;
        this.name = name == null ? "" : name.strip();
        this.bio = bio == null ? "" : bio.strip();
        this.company = company == null ? "" : company.strip();
        this.location = location == null ? "" : location.strip();
        this.blog = blog == null ? "" : blog.strip();
        this.publicRepos = publicRepos;
        this.publicGists = publicGists;
        this.followers = followers;
        this.following = following;
        this.createdAt = createdAt;
        this.fetchedAt = fetchedAt;
        this.avatar = avatar;
        this.profileRepository = profileRepository;
    }

    public String displayName() { return name.isBlank() ? login : name; }
    public String url() { return "https://github.com/" + login; }
}
