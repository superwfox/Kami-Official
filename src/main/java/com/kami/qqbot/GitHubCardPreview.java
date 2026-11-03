package com.kami.qqbot;

import java.nio.file.*;

/** Local preview using real public data; never starts the QQ client or sends messages. */
public final class GitHubCardPreview {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("用法：GitHubCardPreview owner/repo或GitHub链接 [输出目录]");
        GitHubApi api = new GitHubApi(System.getenv("GITHUB_TOKEN"));
        GitHubApi.Target target = GitHubApi.targetIn(args[0]);
        Path destination = Path.of(args.length > 1 ? args[1] : "target/github-preview");
        Files.createDirectories(destination);
        GitHubCardRenderer renderer = new GitHubCardRenderer(api);
        if (target != null && target.user()) {
            GitHubUser user = api.fetchUser(target.login);
            byte[] png = renderer.render(user, 2);
            String readme = user.profileRepository == null ? GitHubUser.MISSING_PROFILE_README : user.profileRepository.readme;
            Files.write(destination.resolve("card.png"), png);
            Files.writeString(destination.resolve("README-source.md"), readme);
            System.out.println(user.url() + " | repos=" + user.publicRepos + " followers=" + user.followers
                    + " | profileRepo=" + (user.profileRepository == null ? "none" : user.profileRepository.fullName())
                    + " | README=" + readme.length() + " chars | images=1 | bytes=" + png.length);
            return;
        }
        String resolved = target == null ? args[0] : target.repository;
        GitHubRepository repository = api.fetch(resolved);
        byte[] png = renderer.render(repository, 2);
        Files.write(destination.resolve("card.png"), png);
        Files.writeString(destination.resolve("README-source.md"), repository.readme);
        System.out.println(repository.fullName() + " @ " + repository.sha + " | " + repository.buildLabel + "=" + repository.buildState
                + " | README=" + repository.readme.length() + " chars | contributors=" + repository.contributors.size() + " | images=1 | bytes=" + png.length);
    }
}
