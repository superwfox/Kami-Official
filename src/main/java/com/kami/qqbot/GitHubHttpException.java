package com.kami.qqbot;

import java.io.IOException;

/** GitHub API HTTP failure. {@code path} is the API path passed to the request, without credentials. */
final class GitHubHttpException extends IOException {
    final int status;
    final String path;

    GitHubHttpException(int status, String path, String message) {
        super(message);
        this.status = status;
        this.path = path;
    }
}
