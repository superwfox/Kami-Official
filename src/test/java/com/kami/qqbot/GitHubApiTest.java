package com.kami.qqbot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GitHubApiTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final String readme = "# 完整 README\n\n" + "完整正文，不能删去。\n\n".repeat(1600) + "全文最后一行 END_OF_README";
    private boolean empty, hiddenChecks, legacyFailure, privateRepo, organization;
    private int profileStatus = 200;
    private String profile = "{\"name\":\"演示昵称\"}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(), query = exchange.getRequestURI().getQuery();
            requests.add(exchange.getRequestURI().toString());
            int status = 200; String body;
            if (path.equals("/repos/demo/project")) body = "{\"full_name\":\"demo/project\",\"default_branch\":\"main\",\"stargazers_count\":1001,\"archived\":true,\"private\":" + privateRepo + ",\"owner\":{\"login\":\"demo\",\"type\":\"" + (organization ? "Organization" : "User") + "\"}}";
            else if (path.equals("/users/demo") || path.equals("/orgs/demo")) { status = profileStatus; body = profile; }
            else if (path.endsWith("/commits")) { status = empty ? 409 : 200; body = empty ? "{}" : "[{\"sha\":\"abcdef123456\",\"commit\":{\"committer\":{\"date\":\"2026-10-21T08:00:00Z\"}}}]"; }
            else if (path.endsWith("/readme")) body = "{\"path\":\"docs/README.md\",\"encoding\":\"base64\",\"content\":\"" + Base64.getEncoder().encodeToString(readme.getBytes(StandardCharsets.UTF_8)) + "\"}";
            else if (path.endsWith("/contributors")) {
                JsonArray people = new JsonArray();
                if (!empty) for (int i = 0; i < (query.endsWith("page=1") ? 100 : 1); i++) { JsonObject person = new JsonObject(); person.addProperty("login", "person" + (query.endsWith("page=1") ? i : 100)); people.add(person); }
                body = people.toString();
            } else if (path.endsWith("/actions/runs")) {
                if (hiddenChecks) { status = 403; body = "{}"; }
                else if (legacyFailure) body = "{\"workflow_runs\":[]}";
                else body = "{\"workflow_runs\":[" + run(1, "Build", "completed", "success", "abcdef123456") + "," + run(2, "Run Bot", "in_progress", null, "abcdef123456") + "]}";
            } else if (path.endsWith("/check-runs")) {
                status = hiddenChecks ? 403 : 200;
                body = hiddenChecks ? "{}" : "{\"check_runs\":[{\"name\":\"test\",\"status\":\"completed\",\"conclusion\":\"success\"}]}";
            } else if (path.endsWith("/status")) body = legacyFailure ? "{\"statuses\":[{\"state\":\"failure\"}]}" : "{\"statuses\":[]}";
            else { status = 404; body = "{}"; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private GitHubApi api() { return new GitHubApi(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), null, Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC)); }

    @Test void preservesTheEntireReadmeAllContributorsAndTheExactCommitResult() throws Exception {
        GitHubApi api = api(); GitHubRepository data = api.fetch("demo/project");
        assertEquals(readme, data.readme);
        assertTrue(data.readme.endsWith("END_OF_README"));
        assertEquals(101, data.contributors.size());
        assertEquals(GitHubRepository.BuildState.SUCCESS, data.buildState);
        assertEquals("Build", data.buildLabel);
        assertTrue(requests.contains("/repos/demo/project/readme?ref=abcdef123456"));
        assertTrue(requests.stream().anyMatch(value -> value.contains("head_sha=abcdef123456")));
        int calls = requests.size(); assertSame(data, api.fetch("demo/project")); assertEquals(calls, requests.size());
    }
    @Test void privateRepositoryIsNotExposedToTheChat() { privateRepo = true; assertThrows(IOException.class, () -> api().fetch("demo/project")); }
    @Test void ownerNicknameDoesNotReplaceTheCanonicalRepositoryIdentity() throws Exception {
        GitHubRepository data = api().fetch("demo/project");
        assertEquals("演示昵称", data.ownerName);
        assertEquals("demo", data.owner);
        assertEquals("demo/project", data.fullName());
        assertEquals("https://github.com/demo/project", data.url());
        assertTrue(requests.contains("/users/demo"));
    }
    @Test void organizationDisplayNameUsesTheOrganizationProfile() throws Exception {
        organization = true; profile = "{\"name\":\"演示组织\"}";
        assertEquals("演示组织", api().fetch("demo/project").ownerName);
        assertTrue(requests.contains("/orgs/demo"));
        assertFalse(requests.contains("/users/demo"));
    }
    @Test void unavailableOrEmptyNicknamesFallBackToTheLogin() throws Exception {
        for (String value : List.of("{}", "{\"name\":null}", "{\"name\":\"   \"}")) {
            profile = value;
            assertEquals("demo", api().fetch("demo/project").ownerName);
        }
        profileStatus = 403;
        assertEquals("demo", api().fetch("demo/project").ownerName);
    }
    @Test void missingChecksDoNotInventSuccess() throws Exception { hiddenChecks = true; assertEquals(GitHubRepository.BuildState.NONE, api().fetch("demo/project").buildState); }
    @Test void legacyFailureIsNotHiddenBySuccessfulActionsCheck() throws Exception { legacyFailure = true; assertEquals(GitHubRepository.BuildState.FAILURE, api().fetch("demo/project").buildState); }
    @Test void emptyRepositoryHasNoCommitReadmeOrCiMark() throws Exception {
        empty = true; GitHubRepository data = api().fetch("demo/project");
        assertEquals("", data.sha); assertNull(data.committedAt); assertEquals("", data.readme); assertEquals(GitHubRepository.BuildState.NONE, data.buildState);
    }
    @Test void linksAndRelativeImagesAreResolvedCorrectly() {
        assertEquals("demo/project", GitHubApi.repositoryIn("看看 https://github.com/demo/project/tree/main。"));
        assertEquals("demo/project", GitHubApi.repositoryIn("<https://github.com/demo/project.git>"));
        assertEquals("demo/project", GitHubApi.repositoryIn("https://github.com/demo/project\u00a0末尾空格"));
        assertNull(GitHubApi.repositoryIn("https://github.com.attacker.example/demo/project"));
        assertNull(GitHubApi.repositoryIn("https://github.com/demo/.."));
        GitHubRepository data = fixture("text");
        assertEquals("https://raw.githubusercontent.com/demo/project/abcdef123456/docs/images/test.png", GitHubApi.imageUri(data, "images/test.png").toString());
        assertEquals("https://raw.githubusercontent.com/demo/project/abcdef123456/assets/test.png", GitHubApi.imageUri(data, "/assets/test.png").toString());
    }
    @Test void personalProfileLinksResolveToTheirSameNameReadmeRepositoryInMessageOrder() {
        for (String link : List.of("https://github.com/superwfox", "https://github.com/superwfox/",
                "https://github.com/superwfox?tab=repositories", "<https://www.github.com/superwfox/#readme>",
                "https://github.com/superwfox\u00a0末尾空格", "https://github.com/superwfox\u3000全角空格"))
            assertEquals("superwfox/superwfox", GitHubApi.repositoryIn(link));
        assertEquals("superwfox/superwfox", GitHubApi.repositoryIn("先看 https://github.com/superwfox 再看 https://github.com/demo/project"));
        assertEquals("demo/project", GitHubApi.repositoryIn("先看 https://github.com/demo/project 再看 https://github.com/superwfox"));
        for (String link : List.of("https://github.com/", "https://github.com/login", "https://github.com/features/actions",
                "https://github.com/settings/profile", "https://github.com/demo/..", "https://github.com.attacker.example/demo"))
            assertNull(GitHubApi.repositoryIn(link));
        assertEquals("superwfox/superwfox", GitHubApi.repositoryIn("https://github.com/features https://github.com/superwfox"));
    }
    @Test void pendingCancelledAndOldCommitRunsCannotBeShownAsPassed() {
        assertEquals(GitHubRepository.BuildState.NONE, GitHubApi.summarize(List.of(run(1,"Build","in_progress",null,"sha")), "status", "conclusion"));
        assertEquals(GitHubRepository.BuildState.NONE, GitHubApi.summarize(List.of(run(1,"Build","completed","cancelled","sha")), "status", "conclusion"));
        JsonObject old = run(1,"Build","completed","success","sha"), retry = run(1,"Build","completed","failure","sha");
        retry.addProperty("run_attempt", 2);
        List<JsonObject> selected = GitHubApi.latestBuildRuns(List.of(old,retry,run(2,"Build","completed","success","other")),"sha");
        assertEquals(GitHubRepository.BuildState.FAILURE, GitHubApi.summarize(selected,"status","conclusion"));
    }
    static GitHubRepository fixture(String markdown) {
        return new GitHubRepository("demo","演示作者","project","描述","main","abcdef123456",Instant.parse("2026-10-21T08:00:00Z"),1001,true,List.of(new GitHubRepository.Contributor("demo",null)),markdown,"docs/README.md",GitHubRepository.BuildState.SUCCESS,"Build",Instant.parse("2026-10-21T12:00:00Z"));
    }
    private static JsonObject run(int id, String name, String status, String conclusion, String sha) {
        JsonObject run = new JsonObject(); run.addProperty("id",id); run.addProperty("workflow_id",id); run.addProperty("run_attempt",1); run.addProperty("name",name); run.addProperty("status",status); run.addProperty("conclusion",conclusion); run.addProperty("head_sha",sha); return run;
    }
}
