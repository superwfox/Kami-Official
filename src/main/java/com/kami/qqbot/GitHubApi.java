package com.kami.qqbot;

import com.google.gson.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Public GitHub metadata only. Optional fields never turn an unknown CI result into success. */
public final class GitHubApi {
    // QQ may append invisible format characters and U+FFFC to copied links.
    private static final Pattern LINK = Pattern.compile("https://(?:www\\.)?github\\.com/([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9_.-]{1,100})(?=$|[/\\s\\p{Z}\\p{Cf}\\x{FFFC}?#<>)\\]，。；,:!])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROFILE_LINK = Pattern.compile("https://(?:www\\.)?github\\.com/([A-Za-z0-9][A-Za-z0-9-]{0,38})/?(?=$|[\\s\\p{Z}\\p{Cf}\\x{FFFC}?#<>)\\]，。；,:!])", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SITE_ROUTES = Set.of("about", "apps", "business", "codespaces", "collections", "contact",
            "customer-stories", "dashboard", "enterprise", "events", "explore", "features", "issues", "join", "login", "logout",
            "marketplace", "new", "notifications", "organizations", "orgs", "pricing", "pulls", "readme", "search", "security",
            "settings", "signup", "sponsors", "stars", "topics", "trending", "users", "watching");
    private static final Pattern BUILD_NAME = Pattern.compile("(?iu)(^|[^a-z])(build|ci|test|tests|verify|check|checks|lint)([^a-z]|$)|构建|测试|检查");
    private final HttpClient http;
    private final URI base;
    private final String token;
    private final Clock clock;
    private final Map<String, GitHubRepository> cache = new LinkedHashMap<>();

    public GitHubApi(String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://api.github.com/"), token, Clock.systemUTC());
    }

    GitHubApi(HttpClient http, URI base, String token, Clock clock) {
        this.http = http; this.base = base; this.token = token; this.clock = clock;
    }

    /** Repository links and personal profile links; profiles resolve to the same-name README repository. */
    public static String repositoryIn(String text) {
        String content = text == null ? "" : text;
        Matcher matcher = LINK.matcher(content);
        String repository = null;
        int position = Integer.MAX_VALUE;
        while (matcher.find()) {
            String name = matcher.group(2);
            if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
            if (!SITE_ROUTES.contains(matcher.group(1).toLowerCase(Locale.ROOT)) && !name.isBlank() && !name.equals(".") && !name.equals("..")) {
                repository = matcher.group(1) + "/" + name;
                position = matcher.start();
                break;
            }
        }
        Matcher profile = PROFILE_LINK.matcher(content);
        while (profile.find() && profile.start() < position) {
            String login = profile.group(1);
            if (!SITE_ROUTES.contains(login.toLowerCase(Locale.ROOT))) return login + "/" + login;
        }
        return repository;
    }

    public GitHubRepository fetch(String fullName) throws IOException, InterruptedException {
        if (!fullName.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
                || fullName.endsWith("/.") || fullName.endsWith("/.."))
            throw new IOException("无效的 GitHub 仓库名称");
        synchronized (cache) {
            GitHubRepository previous = cache.get(fullName.toLowerCase(Locale.ROOT));
            if (previous != null && Duration.between(previous.fetchedAt, clock.instant()).toMinutes() < 5) return previous;
        }
        String path = "repos/" + fullName;
        JsonObject repo = get(path).getAsJsonObject();
        if (repo.has("private") && repo.get("private").getAsBoolean()) throw new IOException("仅支持公开的 GitHub 仓库");
        String canonical = string(repo, "full_name", fullName);
        String branch = string(repo, "default_branch", "");
        JsonArray commits = get(path + "/commits?per_page=1", true).getAsJsonArray();
        String sha = "";
        Instant committed = null;
        if (!commits.isEmpty()) {
            JsonObject head = commits.get(0).getAsJsonObject();
            sha = string(head, "sha", "");
            committed = instant(head.getAsJsonObject("commit").getAsJsonObject("committer"), "date");
        }
        String readme = "", readmePath = "README.md";
        if (!sha.isEmpty()) {
            JsonElement element = optional(path + "/readme?ref=" + sha);
            if (element != null && element.isJsonObject()) {
                JsonObject document = element.getAsJsonObject();
                readmePath = string(document, "path", "README.md");
                if ("base64".equals(string(document, "encoding", ""))) {
                    readme = new String(Base64.getMimeDecoder().decode(string(document, "content", "")), StandardCharsets.UTF_8);
                } else {
                    // Large README files can omit the base64 field; request raw bytes rather than an excerpt.
                    readme = new String(request(path + "/readme?ref=" + sha, "application/vnd.github.raw+json", false).body(), StandardCharsets.UTF_8);
                }
            }
        }
        List<GitHubRepository.Contributor> contributors = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonElement element = optional(path + "/contributors?per_page=100&page=" + page);
            if (element == null || !element.isJsonArray()) break;
            JsonArray people = element.getAsJsonArray();
            for (JsonElement person : people) {
                JsonObject account = person.getAsJsonObject();
                String login = string(account, "login", "");
                if (!login.isEmpty()) contributors.add(new GitHubRepository.Contributor(login,
                        asset(string(account, "avatar_url", "") + "&s=96")));
            }
            if (people.size() < 100) break;
        }
        if (contributors.isEmpty() && repo.has("owner")) {
            JsonObject owner = repo.getAsJsonObject("owner");
            contributors.add(new GitHubRepository.Contributor(string(owner, "login", fullName.split("/")[0]),
                    asset(string(owner, "avatar_url", "") + "&s=96")));
        }
        GitHubRepository.BuildState build = GitHubRepository.BuildState.NONE;
        String buildLabel = "CI";
        if (!sha.isEmpty()) {
            List<JsonObject> runs = new ArrayList<>();
            for (int page = 1; ; page++) {
                JsonElement result = optional(path + "/actions/runs?head_sha=" + sha + "&per_page=100&page=" + page);
                JsonArray batch = array(result, "workflow_runs");
                if (batch == null) break;
                for (JsonElement run : batch) if (run.isJsonObject()) runs.add(run.getAsJsonObject());
                if (batch.size() < 100) break;
            }
            List<JsonObject> buildRuns = latestBuildRuns(runs, sha);
            if (!buildRuns.isEmpty()) {
                build = summarize(buildRuns, "status", "conclusion");
                buildLabel = buildRuns.size() == 1 ? string(buildRuns.get(0), "name", "CI") : "CI";
            } else {
                // Check runs and legacy commit statuses are distinct APIs. Neither includes the other.
                List<JsonObject> allChecks = new ArrayList<>();
                boolean checksKnown = true;
                for (int page = 1; ; page++) {
                    JsonElement checks = optional(path + "/commits/" + sha + "/check-runs?filter=latest&per_page=100&page=" + page);
                    JsonArray batch = array(checks, "check_runs");
                    if (batch == null) { checksKnown = false; break; }
                    for (JsonElement check : batch) if (check.isJsonObject()) allChecks.add(check.getAsJsonObject());
                    if (batch.size() < 100) break;
                }
                boolean statusesKnown = true;
                for (int page = 1; ; page++) {
                    JsonElement combined = optional(path + "/commits/" + sha + "/status?per_page=100&page=" + page);
                    JsonArray statuses = array(combined, "statuses");
                    if (statuses == null) { statusesKnown = false; break; }
                    for (JsonElement status : statuses) {
                        JsonObject normalized = new JsonObject();
                        String state = string(status.getAsJsonObject(), "state", "pending");
                        normalized.addProperty("status", state.equals("pending") ? "in_progress" : "completed");
                        normalized.addProperty("conclusion", state.equals("error") ? "failure" : state);
                        allChecks.add(normalized);
                    }
                    if (statuses.size() < 100) break;
                }
                GitHubRepository.BuildState known = summarize(allChecks, "status", "conclusion");
                if (known == GitHubRepository.BuildState.FAILURE || checksKnown && statusesKnown) build = known;
            }
        }
        String[] names = canonical.split("/", 2);
        JsonObject owner = repo.getAsJsonObject("owner");
        String accountPath = "Organization".equals(string(owner, "type", "")) ? "orgs/" : "users/";
        JsonElement profile = optional(accountPath + names[0]);
        String ownerName = profile != null && profile.isJsonObject() ? string(profile.getAsJsonObject(), "name", names[0]) : names[0];
        GitHubRepository snapshot = new GitHubRepository(names[0], ownerName, names[1], string(repo, "description", ""), branch, sha,
                committed, repo.has("stargazers_count") ? repo.get("stargazers_count").getAsLong() : 0,
                repo.has("archived") && repo.get("archived").getAsBoolean(), contributors, readme, readmePath,
                build, buildLabel, clock.instant());
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> Duration.between(entry.getValue().fetchedAt, clock.instant()).toMinutes() >= 5);
            if (cache.size() >= 64) cache.remove(cache.keySet().iterator().next());
            cache.put(fullName.toLowerCase(Locale.ROOT), snapshot);
        }
        return snapshot;
    }

    static List<JsonObject> latestBuildRuns(List<JsonObject> runs, String sha) {
        Map<String, JsonObject> latest = new LinkedHashMap<>();
        for (JsonObject run : runs) {
            if (!sha.equals(string(run, "head_sha", "")) || !BUILD_NAME.matcher(string(run, "name", "")).find()) continue;
            String key = string(run, "workflow_id", string(run, "name", ""));
            JsonObject old = latest.get(key);
            long id = number(run, "id"), attempt = number(run, "run_attempt");
            if (old == null || id > number(old, "id") || (id == number(old, "id") && attempt > number(old, "run_attempt"))) latest.put(key, run);
        }
        return new ArrayList<>(latest.values());
    }

    static GitHubRepository.BuildState summarize(List<JsonObject> results, String statusKey, String conclusionKey) {
        boolean success = false, incomplete = false;
        for (JsonObject result : results) {
            String status = string(result, statusKey, ""), conclusion = string(result, conclusionKey, "");
            if (status.equals("completed") && List.of("failure", "timed_out", "action_required", "startup_failure").contains(conclusion)) return GitHubRepository.BuildState.FAILURE;
            if (!status.equals("completed") || !List.of("success", "neutral", "skipped").contains(conclusion)) incomplete = true;
            if (status.equals("completed") && conclusion.equals("success")) success = true;
        }
        return success && !incomplete ? GitHubRepository.BuildState.SUCCESS : GitHubRepository.BuildState.NONE;
    }

    /** Resolve README images against the pinned commit and README directory, never against mutable HEAD. */
    public static URI imageUri(GitHubRepository repo, String destination) {
        try {
            URI uri = URI.create(destination);
            URI directory = URI.create("https://raw.githubusercontent.com/" + repo.fullName() + "/" + repo.sha + "/" + repo.readmePath).resolve(".");
            if (destination.startsWith("/")) uri = URI.create("https://raw.githubusercontent.com/" + repo.fullName() + "/" + repo.sha + destination);
            else if (!uri.isAbsolute()) uri = directory.resolve(uri);
            if (uri.getHost() != null && uri.getHost().equalsIgnoreCase("github.com") && uri.getPath().contains("/blob/"))
                uri = URI.create("https://raw.githubusercontent.com" + uri.getPath().replaceFirst("/blob/", "/"));
            return uri;
        } catch (IllegalArgumentException e) { return null; }
    }

    /** Fetch public images with a small, validated redirect chain and no API credentials. */
    public BufferedImage asset(String url) throws InterruptedException {
        try {
            URI uri = URI.create(url);
            Set<URI> visited = new HashSet<>();
            for (int redirects = 0; redirects <= 3; redirects++) {
                if (!allowedAssetUri(uri) || !visited.add(uri)) return null;
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "Kami-Official-repository-card").GET().build();
                URI next = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                        try (InputStream input = response.body()) {
                            int status = response.statusCode();
                            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                                String location = response.headers().firstValue("Location").orElse("");
                                if (location.isBlank()) return null;
                                next = uri.resolve(location);
                                break;
                            }
                            if (status >= 500 && attempt == 0) continue;
                            if (status != 200) return null;
                            byte[] bytes = input.readNBytes(10 * 1024 * 1024 + 1);
                            if (bytes.length > 10 * 1024 * 1024) return null;
                            return GitHubImageDecoder.decode(bytes);
                        }
                    } catch (IOException e) { if (attempt == 1) return null; }
                }
                if (next == null) return null;
                uri = next;
            }
            return null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private static boolean allowedAssetUri(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getPort() == -1
                && Set.of("avatars.githubusercontent.com", "raw.githubusercontent.com", "user-images.githubusercontent.com",
                "private-user-images.githubusercontent.com", "camo.githubusercontent.com", "github.com", "img.shields.io",
                "badgen.net", "flat.badgen.net", "github-production-user-asset-6210df.s3.amazonaws.com").contains(host);
    }

    private JsonElement get(String path) throws IOException, InterruptedException { return get(path, false); }
    private JsonElement get(String path, boolean emptyRepository) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = request(path, "application/vnd.github+json", emptyRepository);
        if (response.statusCode() == 204 || (emptyRepository && response.statusCode() == 409)) return new JsonArray();
        try { return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)); }
        catch (JsonParseException e) { throw new IOException("GitHub 返回了无法解析的数据"); }
    }
    private JsonElement optional(String path) throws InterruptedException {
        try { return get(path); } catch (IOException e) { return null; }
    }
    private HttpResponse<byte[]> request(String path, String accept, boolean allowEmpty) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Kami-Official-repository-card");
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token.trim());
        HttpResponse<byte[]> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status >= 200 && status < 300 || allowEmpty && status == 409) return response;
        if (status == 404) throw new IOException("GitHub 仓库或内容不存在，或没有访问权限");
        if (status == 403 || status == 429) throw new IOException("GitHub 暂时限流或无访问权限，请稍后重试");
        throw new IOException("GitHub 请求失败，HTTP " + status);
    }
    private static JsonArray array(JsonElement object, String key) {
        if (object == null || !object.isJsonObject()) return null;
        JsonElement value = object.getAsJsonObject().get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }
    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString();
    }
    private static long number(JsonObject object, String key) { try { return Long.parseLong(string(object, key, "0")); } catch (NumberFormatException e) { return 0; } }
    private static Instant instant(JsonObject object, String key) { try { return Instant.parse(string(object, key, "")); } catch (DateTimeException e) { return null; } }
}
