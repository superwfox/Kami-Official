package com.kami.qqbot;

import java.nio.file.*;

/** Local preview using real public data; never starts the QQ client or sends messages. */
public final class GitHubCardPreview {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("用法：GitHubCardPreview owner/repo [输出目录]");
        GitHubApi api = new GitHubApi(System.getenv("GITHUB_TOKEN"));
        GitHubRepository repository = api.fetch(args[0]);
        Path destination = Path.of(args.length > 1 ? args[1] : "target/github-preview");
        Files.createDirectories(destination);
        byte[] png = new GitHubCardRenderer(api).render(repository, 2);
        Files.write(destination.resolve("card.png"), png);
        Files.writeString(destination.resolve("README-source.md"), repository.readme);
        System.out.println(repository.fullName() + " @ " + repository.sha + " | " + repository.buildLabel + "=" + repository.buildState
                + " | README=" + repository.readme.length() + " chars | contributors=" + repository.contributors.size() + " | images=1 | bytes=" + png.length);
    }
}
