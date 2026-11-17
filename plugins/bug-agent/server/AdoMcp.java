/*
 * bug-agent MCP server - single Java source file, no dependencies.
 * Runs with the JDK source launcher (Java 17+):  java AdoMcp.java
 *
 * Azure DevOps:  check_connection, list_new_bugs, get_bug, add_comment
 * Projects:      get_projects, set_projects
 * Fix workdirs:  prepare_workdir, finalize_workdir   (git worktree or overlay copy)
 * Memory:        record_bug, reset_bug, history
 *
 * Settings come from env vars that plugin.json's userConfig fills in.
 * Transport: MCP over stdio, newline-delimited JSON-RPC 2.0.
 */
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AdoMcp {

    // ------------------------------------------------------------------ config
    static String opt(String name, String def) {
        String v = System.getenv(name);
        v = v == null ? "" : v.trim();
        return v.isEmpty() || v.startsWith("${") ? def : v; // unset userConfig may arrive unsubstituted
    }

    static final String ORG = opt("AZURE_DEVOPS_ORG", "");
    static final String PROJECT = opt("AZURE_DEVOPS_PROJECT", "");
    static final String PAT = opt("AZURE_DEVOPS_PAT", "");
    static final String ASSIGNED_TO = opt("AZURE_DEVOPS_ASSIGNED_TO", "@Me");
    static final List<String> STATES = Arrays.stream(opt("AZURE_DEVOPS_STATES", "New").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    static final boolean POST_COMMENTS = List.of("true", "1", "yes").contains(opt("BUG_AGENT_POST_COMMENTS", "false").toLowerCase());
    static final int MAX_ATTEMPTS = Integer.parseInt(opt("BUG_AGENT_MAX_ATTEMPTS", "3"));
    static final String BASE_URL = opt("AZURE_DEVOPS_BASE_URL", "https://dev.azure.com").replaceAll("/+$", "");
    static final Path DATA_DIR = Paths.get(opt("BUG_AGENT_DATA", Paths.get(System.getProperty("user.home"), ".bug-agent").toString()))
            .toAbsolutePath().normalize();
    static final Path STATE_FILE = DATA_DIR.resolve("state.json");
    static final Path PROJECTS_FILE = DATA_DIR.resolve("projects.json");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // ------------------------------------------------------------------ entry point
    public static void main(String[] args) throws IOException {
        Files.createDirectories(DATA_DIR);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.replace("﻿", "");
            if (line.isBlank()) continue;
            Object req;
            try {
                req = Json.parse(line);
            } catch (RuntimeException e) {
                out.println(Json.write(rpc(null, null, error(-32700, "parse error"))));
                continue;
            }
            Map<String, Object> res = handle(asMap(req));
            if (res != null) out.println(Json.write(res));
        }
    }

    static Map<String, Object> rpc(Object id, Object result, Map<String, Object> error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        if (error != null) m.put("error", error);
        else m.put("result", result);
        return m;
    }

    static Map<String, Object> error(int code, String message) {
        return map("code", (long) code, "message", message);
    }

    static Map<String, Object> handle(Map<String, Object> req) {
        if (!req.containsKey("id")) return null; // notification
        Object id = req.get("id");
        String method = String.valueOf(req.get("method"));
        Map<String, Object> params = req.get("params") instanceof Map ? asMap(req.get("params")) : Map.of();
        try {
            switch (method) {
                case "initialize": {
                    Object pv = params.getOrDefault("protocolVersion", "2025-06-18");
                    return rpc(id, map("protocolVersion", pv, "capabilities", map("tools", map()),
                            "serverInfo", map("name", "bug-agent-ado", "version", "1.1.0")), null);
                }
                case "ping":
                    return rpc(id, map(), null);
                case "tools/list": {
                    List<Object> tools = new ArrayList<>();
                    for (Tool t : TOOLS.values()) tools.add(map("name", t.name, "description", t.description, "inputSchema", t.schema));
                    return rpc(id, map("tools", tools), null);
                }
                case "tools/call": {
                    Tool tool = TOOLS.get(String.valueOf(params.get("name")));
                    if (tool == null) return rpc(id, null, error(-32602, "unknown tool " + params.get("name")));
                    Map<String, Object> a = params.get("arguments") instanceof Map ? asMap(params.get("arguments")) : new LinkedHashMap<>();
                    try {
                        Object outv = tool.run.apply(a);
                        return rpc(id, map("content", List.of(map("type", "text", "text", Json.write(outv, true)))), null);
                    } catch (Exception e) {
                        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                        return rpc(id, map("isError", true, "content", List.of(map("type", "text", "text", "ERROR: " + msg))), null);
                    }
                }
                default:
                    return rpc(id, null, error(-32601, "method not found: " + method));
            }
        } catch (Exception e) {
            return rpc(id, null, error(-32603, String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------ tools
    record Tool(String name, String description, Object schema, Function<Map<String, Object>, Object> run) {}

    static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static void tool(String name, String description, String schemaJson, Function<Map<String, Object>, Object> run) {
        TOOLS.put(name, new Tool(name, description, Json.parse(schemaJson), run));
    }

    static {
        String empty = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

        tool("check_connection",
                "Check the Azure DevOps settings and count the bugs that match the query (assigned to me, configured states).",
                empty, a -> {
                    List<Long> ids = queryIds();
                    return map("ok", true, "org", ORG, "project", PROJECT, "assignedTo", ASSIGNED_TO, "states", STATES,
                            "matchingBugs", (long) ids.size(), "dataDir", DATA_DIR.toString());
                });

        tool("list_new_bugs",
                "List bugs assigned to me in the configured states (default New) that bug-agent has not handled yet, with title, link, priority, description and repro steps. Bugs whose last attempt errored are retried up to the attempt limit.",
                """
                {"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":50,"default":5}},"additionalProperties":false}
                """, a -> {
                    int limit = (int) num(a.get("limit"), 5);
                    List<Long> ids = queryIds();
                    Map<String, Object> state = loadState();
                    List<Long> fresh = ids.stream().filter(id -> shouldProcess(state, id)).collect(Collectors.toList());
                    return map("matching", (long) ids.size(), "alreadyHandled", (long) (ids.size() - fresh.size()),
                            "deferred", (long) Math.max(0, fresh.size() - limit),
                            "bugs", getItems(fresh.subList(0, Math.min(limit, fresh.size()))));
                });

        tool("get_bug", "Get one bug's details and link by id.",
                """
                {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}
                """, a -> {
                    long id = (long) num(a.get("id"), -1);
                    List<Object> items = getItems(List.of(id));
                    return items.isEmpty() ? map("error", "bug " + id + " not found") : items.get(0);
                });

        tool("add_comment", "Post a comment on a bug. Only works when the post_comments plugin option is enabled.",
                """
                {"type":"object","properties":{"id":{"type":"integer"},"text":{"type":"string","description":"Plain text; blank lines separate paragraphs"}},"required":["id","text"],"additionalProperties":false}
                """, a -> {
                    if (!POST_COMMENTS) return map("skipped", true, "reason", "post_comments option is off");
                    long id = (long) num(a.get("id"), -1);
                    String html = Arrays.stream(String.valueOf(a.get("text")).split("\\n{2,}"))
                            .map(p -> "<p>" + escHtml(p).replace("\n", "<br>") + "</p>").collect(Collectors.joining());
                    ado("POST", projUrl() + "/_apis/wit/workItems/" + id + "/comments?api-version=7.1-preview.4", Json.write(map("text", html)));
                    return map("posted", true);
                });

        tool("get_projects", "List the configured projects (name, path, description, applyMode, isGit).", empty, a -> {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> p : loadProjects()) {
                Map<String, Object> m = new LinkedHashMap<>(p);
                Path path = Paths.get(String.valueOf(p.get("path")));
                m.put("isGit", gitTop(path) != null);
                m.put("exists", Files.exists(path));
                out.add(m);
            }
            return map("projects", out, "dataDir", DATA_DIR.toString());
        });

        tool("set_projects",
                "Replace the project list. Each path must be an existing folder. applyMode: auto (worktree if git, else overlay), worktree, or overlay.",
                """
                {"type":"object","properties":{"projects":{"type":"array","items":{"type":"object","properties":{
                  "name":{"type":"string","pattern":"^[A-Za-z0-9._-]+$"},
                  "path":{"type":"string"},
                  "description":{"type":"string"},
                  "applyMode":{"type":"string","enum":["auto","worktree","overlay"],"default":"auto"}},
                  "required":["name","path","description"],"additionalProperties":false}}},
                 "required":["projects"],"additionalProperties":false}
                """, a -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    Set<String> names = new HashSet<>();
                    List<String> bad = new ArrayList<>();
                    for (Object o : asList(a.get("projects"))) {
                        Map<String, Object> p = asMap(o);
                        String name = String.valueOf(p.get("name"));
                        if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("invalid project name: " + name);
                        if (!names.add(name)) throw new IllegalArgumentException("project names must be unique");
                        Path path = Paths.get(String.valueOf(p.get("path"))).toAbsolutePath().normalize();
                        if (!Files.isDirectory(path)) bad.add(path.toString());
                        String mode = String.valueOf(p.getOrDefault("applyMode", "auto"));
                        if (!List.of("auto", "worktree", "overlay").contains(mode)) throw new IllegalArgumentException("invalid applyMode: " + mode);
                        out.add(map("name", name, "path", path.toString(), "description", String.valueOf(p.getOrDefault("description", "")), "applyMode", mode));
                    }
                    if (!bad.isEmpty()) throw new IllegalArgumentException("not a folder: " + String.join(", ", bad));
                    writeJson(PROJECTS_FILE, out);
                    List<Object> saved = new ArrayList<>();
                    for (Map<String, Object> p : out) {
                        Map<String, Object> m = new LinkedHashMap<>(p);
                        m.put("isGit", gitTop(Paths.get(String.valueOf(p.get("path")))) != null);
                        saved.add(m);
                    }
                    return map("saved", saved);
                });

        tool("prepare_workdir",
                "Create the place a fixer edits for one bug in one project, without touching the developer's checkout. Git projects: a worktree on branch ai-fix/bug-<id>. Others: an overlay folder pre-filled with copies of `files`. Returns workdir.",
                """
                {"type":"object","properties":{"bugId":{"type":"integer"},"project":{"type":"string"},
                 "files":{"type":"array","items":{"type":"string"},"description":"Project-relative files the plan touches"}},
                 "required":["bugId","project"],"additionalProperties":false}
                """, a -> prepareWorkdir((long) num(a.get("bugId"), -1), String.valueOf(a.get("project")),
                        a.get("files") == null ? List.of() : asList(a.get("files")).stream().map(String::valueOf).collect(Collectors.toList())));

        tool("finalize_workdir",
                "After the fixer finishes: commit the changed files on the worktree branch (git) or prune unchanged copies and write _FIX_NOTES.md (overlay). Returns filesChanged, branch, commit.",
                """
                {"type":"object","properties":{"bugId":{"type":"integer"},"project":{"type":"string"},"title":{"type":"string"},
                 "url":{"type":"string"},"rootCause":{"type":"string"},"summary":{"type":"string"}},
                 "required":["bugId","project"],"additionalProperties":false}
                """, AdoMcp::finalizeWorkdir);

        tool("record_bug", "Remember the outcome for a bug so later cycles skip it (errors are retried up to the attempt limit).",
                """
                {"type":"object","properties":{"id":{"type":"integer"},"title":{"type":"string"},"url":{"type":"string"},
                 "outcome":{"type":"string","enum":["fixed","partially_fixed","needs_human","error"]},
                 "rootCause":{"type":"string"},"confidence":{"type":"string"},"notes":{"type":"string"},
                 "fixes":{"type":"array","items":{"type":"object"}}},
                 "required":["id","title","outcome"],"additionalProperties":false}
                """, a -> {
                    String outcome = String.valueOf(a.get("outcome"));
                    if (!List.of("fixed", "partially_fixed", "needs_human", "error").contains(outcome))
                        throw new IllegalArgumentException("invalid outcome: " + outcome);
                    long id = (long) num(a.get("id"), -1);
                    Map<String, Object> state = loadState();
                    Map<String, Object> bugs = asMap(state.get("bugs"));
                    Map<String, Object> prev = bugs.get(String.valueOf(id)) instanceof Map ? asMap(bugs.get(String.valueOf(id))) : null;
                    long attempts = (prev == null ? 0 : (long) num(prev.get("attempts"), 0)) + 1;
                    String now = Instant.now().toString();
                    Map<String, Object> rec = new LinkedHashMap<>(a);
                    rec.put("id", id);
                    rec.put("attempts", attempts);
                    rec.put("lastOutcome", outcome);
                    rec.put("updatedAt", now);
                    bugs.put(String.valueOf(id), rec);
                    List<Object> history = new ArrayList<>(asList(state.get("history")));
                    history.add(0, map("id", id, "title", a.get("title"), "outcome", outcome, "at", now));
                    state.put("history", history.subList(0, Math.min(500, history.size())));
                    writeJson(STATE_FILE, state);
                    return map("saved", true, "attempts", attempts);
                });

        tool("reset_bug", "Forget a bug so the next cycle processes it again.",
                """
                {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}
                """, a -> {
                    Map<String, Object> state = loadState();
                    boolean existed = asMap(state.get("bugs")).remove(String.valueOf((long) num(a.get("id"), -1))) != null;
                    writeJson(STATE_FILE, state);
                    return map("reset", existed);
                });

        tool("history", "Show handled bugs with outcome, root cause and where each fix was written.",
                """
                {"type":"object","properties":{"limit":{"type":"integer","default":20}},"additionalProperties":false}
                """, a -> {
                    int limit = (int) num(a.get("limit"), 20);
                    List<Object> bugs = asMap(loadState().get("bugs")).values().stream()
                            .sorted(Comparator.comparing((Object b) -> String.valueOf(asMap(b).get("updatedAt"))).reversed())
                            .limit(limit).collect(Collectors.toList());
                    return map("dataDir", DATA_DIR.toString(), "bugs", bugs);
                });
    }

    // ------------------------------------------------------------------ Azure DevOps
    static void requireAzure() {
        List<String> missing = new ArrayList<>();
        if (ORG.isEmpty()) missing.add("azure_org");
        if (PROJECT.isEmpty()) missing.add("azure_project");
        if (PAT.isEmpty()) missing.add("azure_pat");
        if (!missing.isEmpty())
            throw new IllegalStateException("plugin option(s) not set: " + String.join(", ", missing) + ". Run: claude plugin configure bug-agent");
    }

    static Object ado(String method, String url, String body) {
        requireAzure();
        String auth = Base64.getEncoder().encodeToString((":" + PAT).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        b = body == null ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> res;
        try {
            res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Azure DevOps request failed: " + e.getMessage());
        }
        int s = res.statusCode();
        if (s < 200 || s >= 300 || s == 203) { // 203 = sign-in page: the PAT was rejected
            String hint = (s == 401 || s == 203) ? " (check the PAT and its Work Items scope)" : "";
            String text = res.body() == null ? "" : res.body();
            throw new IllegalStateException("Azure DevOps " + s + hint + ": " + text.substring(0, Math.min(300, text.length())));
        }
        return Json.parse(res.body());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String orgUrl() { return BASE_URL + "/" + enc(ORG); }
    static String projUrl() { return orgUrl() + "/" + enc(PROJECT); }
    static String bugLink(long id) { return projUrl() + "/_workitems/edit/" + id; }

    static String buildWiql(String assignedTo, List<String> states, String project) {
        Function<String, String> q = s -> "'" + s.replace("'", "''") + "'";
        String who = assignedTo.trim().equals("@Me") ? "@Me" : q.apply(assignedTo);
        String stateClause = states.stream().map(s -> "[System.State] = " + q.apply(s)).collect(Collectors.joining(" OR "));
        return "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = " + q.apply(project) + " "
                + "AND [System.WorkItemType] = 'Bug' AND [System.AssignedTo] = " + who + " "
                + "AND (" + stateClause + ") "
                + "ORDER BY [Microsoft.VSTS.Common.Priority] ASC, [System.CreatedDate] ASC";
    }

    static List<Long> queryIds() {
        Object data = ado("POST", projUrl() + "/_apis/wit/wiql?$top=100&api-version=7.1",
                Json.write(map("query", buildWiql(ASSIGNED_TO, STATES, PROJECT))));
        List<Long> ids = new ArrayList<>();
        for (Object w : asList(asMap(data).get("workItems"))) ids.add((long) num(asMap(w).get("id"), -1));
        return ids;
    }

    static List<Object> getItems(List<Long> ids) {
        if (ids.isEmpty()) return new ArrayList<>();
        String fields = String.join(",", "System.Title", "System.State", "System.Description", "System.Tags", "System.AreaPath",
                "Microsoft.VSTS.TCM.ReproSteps", "Microsoft.VSTS.Common.Priority", "Microsoft.VSTS.Common.Severity");
        String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Object data = ado("GET", orgUrl() + "/_apis/wit/workitems?ids=" + idList + "&fields=" + fields + "&errorPolicy=omit&api-version=7.1", null);
        List<Object> out = new ArrayList<>();
        for (Object w : asList(asMap(data).get("value"))) {
            if (w == null) continue;
            Map<String, Object> wi = asMap(w);
            Map<String, Object> f = asMap(wi.get("fields"));
            long id = (long) num(wi.get("id"), -1);
            out.add(map(
                    "id", id,
                    "title", str(f.get("System.Title")),
                    "url", bugLink(id),
                    "state", str(f.get("System.State")),
                    "priority", f.get("Microsoft.VSTS.Common.Priority"),
                    "severity", f.get("Microsoft.VSTS.Common.Severity"),
                    "areaPath", str(f.get("System.AreaPath")),
                    "tags", str(f.get("System.Tags")),
                    "description", cut(stripHtml(str(f.get("System.Description"))), 4000),
                    "reproSteps", cut(stripHtml(str(f.get("Microsoft.VSTS.TCM.ReproSteps"))), 4000)));
        }
        return out;
    }

    static boolean shouldProcess(Map<String, Object> state, long id) {
        Object b = asMap(state.get("bugs")).get(String.valueOf(id));
        if (!(b instanceof Map)) return true;
        Map<String, Object> m = asMap(b);
        return "error".equals(m.get("lastOutcome")) && num(m.get("attempts"), 0) < MAX_ATTEMPTS;
    }

    // ------------------------------------------------------------------ fix workdirs
    static Map<String, Object> projectByName(String name) {
        List<Map<String, Object>> ps = loadProjects();
        for (Map<String, Object> p : ps) if (name.equals(p.get("name"))) return p;
        String known = ps.stream().map(p -> String.valueOf(p.get("name"))).collect(Collectors.joining(", "));
        throw new IllegalArgumentException("unknown project \"" + name + "\". Configured: " + (known.isEmpty() ? "(none - run /bug-agent:setup)" : known));
    }

    static Map<String, Object> prepareWorkdir(long bugId, String project, List<String> files) {
        Map<String, Object> p = projectByName(project);
        Path src = Paths.get(String.valueOf(p.get("path")));
        String mode = String.valueOf(p.getOrDefault("applyMode", "auto"));
        Path top = "overlay".equals(mode) ? null : gitTop(src);
        if ("worktree".equals(mode) && top == null) throw new IllegalArgumentException(src + " is not a git repository");
        try {
            if (top != null) {
                Path sub = top.relativize(src);
                Path wtDir = DATA_DIR.resolve("worktrees").resolve(safeName(top.getFileName().toString()) + "-bug-" + bugId);
                String branch = "ai-fix/bug-" + bugId;
                if (Files.exists(wtDir)) {
                    branch = git(wtDir, "rev-parse", "--abbrev-ref", "HEAD");
                } else {
                    Files.createDirectories(wtDir.getParent());
                    if (!git(top, "branch", "--list", branch).isEmpty()) branch = branch + "-" + Long.toString(System.currentTimeMillis(), 36);
                    git(top, "worktree", "add", "-b", branch, wtDir.toString(), "HEAD");
                }
                return map("mode", "worktree", "workdir", wtDir.resolve(sub).normalize().toString(), "branch", branch, "sourcePath", src.toString());
            }
            Path overlay = DATA_DIR.resolve("patches").resolve("bug-" + bugId).resolve(safeName(project));
            for (String rel : files) {
                Path from = inside(src, rel);
                Path to = inside(overlay, rel);
                if (Files.exists(from) && !Files.exists(to)) {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to);
                }
            }
            Files.createDirectories(overlay);
            return map("mode", "overlay", "workdir", overlay.toString(), "sourcePath", src.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Object finalizeWorkdir(Map<String, Object> a) {
        long bugId = (long) num(a.get("bugId"), -1);
        String project = String.valueOf(a.get("project"));
        String title = strOr(a.get("title")), url = strOr(a.get("url")), rootCause = strOr(a.get("rootCause")), summary = strOr(a.get("summary"));
        Map<String, Object> p = projectByName(project);
        Map<String, Object> w = prepareWorkdir(bugId, project, List.of());
        Path workdir = Paths.get(String.valueOf(w.get("workdir")));
        try {
            if ("worktree".equals(w.get("mode"))) {
                Path wtRoot = gitTop(workdir);
                String sub = wtRoot.relativize(workdir).toString().replace('\\', '/');
                String scope = sub.isEmpty() ? "." : sub;
                LinkedHashSet<String> changed = new LinkedHashSet<>();
                changed.addAll(lines(git(wtRoot, "-c", "core.quotepath=false", "diff", "--name-only", "HEAD", "--", scope)));
                changed.addAll(lines(git(wtRoot, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard", "--", scope)));
                if (changed.isEmpty()) return map("mode", "worktree", "branch", w.get("branch"), "filesChanged", List.of(), "commit", null);
                List<String> add = new ArrayList<>(List.of("add", "--"));
                add.addAll(changed);
                git(wtRoot, add.toArray(new String[0]));
                String msg = ("fix: bug #" + bugId + " " + title + "\n\n" + rootCause + "\n\n" + summary + "\n\n" + url).trim();
                git(wtRoot, "-c", "user.name=bug-agent", "-c", "user.email=bug-agent@localhost", "commit", "-m", msg);
                return map("mode", "worktree", "workdir", workdir.toString(), "branch", w.get("branch"),
                        "commit", git(wtRoot, "rev-parse", "--short", "HEAD"), "filesChanged", new ArrayList<>(changed));
            }
            // overlay: keep only files that differ from the original, then write notes
            Path src = Paths.get(String.valueOf(p.get("path")));
            List<String> changed = new ArrayList<>();
            List<Path> all;
            try (Stream<Path> s = Files.walk(workdir)) {
                all = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
            }
            for (Path copy : all) {
                String rel = workdir.relativize(copy).toString().replace('\\', '/');
                if (rel.equals("_FIX_NOTES.md")) continue;
                Path orig = src.resolve(rel);
                if (Files.exists(orig) && Files.mismatch(orig, copy) == -1) Files.delete(copy);
                else changed.add(rel);
            }
            if (!changed.isEmpty()) {
                String notes = "# Bug #" + bugId + ": " + title + "\n\n" + url + "\n\nCopy these files into " + src + " after review.\n\n"
                        + "## Root cause\n" + rootCause + "\n\n## Changes\n" + summary + "\n\n## Files\n"
                        + changed.stream().map(f -> "- " + f).collect(Collectors.joining("\n")) + "\n";
                Files.writeString(workdir.resolve("_FIX_NOTES.md"), notes, StandardCharsets.UTF_8);
            }
            return map("mode", "overlay", "workdir", workdir.toString(), "filesChanged", changed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ git / files
    static String git(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        try {
            Process proc = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectInput(ProcessBuilder.Redirect.from(nullFile())).start();
            byte[] out = proc.getInputStream().readAllBytes();
            byte[] err = proc.getErrorStream().readAllBytes();
            if (proc.waitFor() != 0)
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + new String(err, StandardCharsets.UTF_8).trim());
            return new String(out, StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("could not run git: " + e.getMessage());
        }
    }

    static File nullFile() {
        return new File(System.getProperty("os.name").toLowerCase().startsWith("win") ? "NUL" : "/dev/null");
    }

    static Path gitTop(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try {
            return Paths.get(git(dir, "rev-parse", "--show-toplevel")).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Path inside(Path root, String rel) {
        String clean = rel.replace('\\', '/').replaceAll("^/+", "");
        Path r = root.toAbsolutePath().normalize();
        Path target = r.resolve(clean).normalize();
        if (!target.startsWith(r) || target.equals(r) && !clean.isEmpty() && !clean.equals("."))
            throw new IllegalArgumentException("path escapes the project: " + rel);
        return target;
    }

    static List<String> lines(String s) {
        return Arrays.stream(s.split("\\r?\\n")).filter(l -> !l.isBlank()).collect(Collectors.toList());
    }

    static String safeName(String s) { return s.replaceAll("[^a-zA-Z0-9._-]+", "-"); }

    static Map<String, Object> loadState() {
        Object s = readJson(STATE_FILE);
        Map<String, Object> state = s instanceof Map ? asMap(s) : new LinkedHashMap<>();
        state.putIfAbsent("bugs", new LinkedHashMap<String, Object>());
        state.putIfAbsent("history", new ArrayList<Object>());
        return state;
    }

    static List<Map<String, Object>> loadProjects() {
        Object p = readJson(PROJECTS_FILE);
        List<Map<String, Object>> out = new ArrayList<>();
        if (p instanceof List) for (Object o : asList(p)) out.add(asMap(o));
        return out;
    }

    static Object readJson(Path f) {
        try {
            return Files.exists(f) ? Json.parse(Files.readString(f, StandardCharsets.UTF_8)) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeJson(Path f, Object v) {
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(v, true), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ text helpers
    static String stripHtml(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h\\d)>", "\n").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&").replaceAll("\\n{3,}", "\n\n").trim();
    }

    static String escHtml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static String strOr(Object o) { return o == null ? "" : String.valueOf(o); }
    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    static double num(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) try { return Double.parseDouble((String) o); } catch (NumberFormatException ignored) {}
        return def;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("expected an object");
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        if (o == null) return new ArrayList<>();
        throw new IllegalArgumentException("expected an array");
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ minimal JSON
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) throw new IllegalArgumentException("trailing characters in JSON");
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String w) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("bad JSON at " + i);
            i += w.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw new IllegalArgumentException("expected : at " + (i - 1));
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i - 1));
            }
        }

        private String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new IllegalArgumentException("bad JSON at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        static String write(Object v) { return write(v, false); }

        static String write(Object v, boolean pretty) {
            StringBuilder b = new StringBuilder();
            write(b, v, pretty, 0);
            return b.toString();
        }

        private static void indent(StringBuilder b, boolean pretty, int depth) {
            if (!pretty) return;
            b.append('\n');
            for (int k = 0; k < depth; k++) b.append("  ");
        }

        private static void write(StringBuilder b, Object v, boolean pretty, int depth) {
            if (v == null) b.append("null");
            else if (v instanceof String) quote(b, (String) v);
            else if (v instanceof Boolean) b.append(v);
            else if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) b.append((long) d);
                else b.append(d);
            } else if (v instanceof Number) b.append(v);
            else if (v instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) v;
                if (m.isEmpty()) { b.append("{}"); return; }
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    indent(b, pretty, depth + 1);
                    quote(b, String.valueOf(e.getKey()));
                    b.append(pretty ? ": " : ":");
                    write(b, e.getValue(), pretty, depth + 1);
                }
                indent(b, pretty, depth);
                b.append('}');
            } else if (v instanceof Collection) {
                Collection<?> l = (Collection<?>) v;
                if (l.isEmpty()) { b.append("[]"); return; }
                b.append('[');
                boolean first = true;
                for (Object o : l) {
                    if (!first) b.append(',');
                    first = false;
                    indent(b, pretty, depth + 1);
                    write(b, o, pretty, depth + 1);
                }
                indent(b, pretty, depth);
                b.append(']');
            } else quote(b, String.valueOf(v));
        }

        private static void quote(StringBuilder b, String s) {
            b.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
        }
    }
}
