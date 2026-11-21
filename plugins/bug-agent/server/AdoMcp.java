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
    /** Work item types treated as the "user story" a bug belongs to (Agile / Scrum / CMMI). */
    static final Set<String> STORY_TYPES = Arrays.stream(opt("BUG_AGENT_STORY_TYPES", "User Story,Product Backlog Item,Requirement").split(","))
            .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
    /** New story branches are named PREFIX + story id, e.g. user-story/12345. */
    static final String BUG_PREFIX = opt("BUG_AGENT_BUG_BRANCH_PREFIX", "fix/us-");
    static final String TASK_PREFIX = opt("BUG_AGENT_TASK_BRANCH_PREFIX", "feature/us-");
    /** Work item types picked up when assigned to the user. */
    static final List<String> WORK_ITEM_TYPES = Arrays.stream(opt("BUG_AGENT_WORK_ITEM_TYPES", "Bug,Task").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());

    /** "task" for Task work items, "bug" for everything else. */
    static String kindOf(String workItemType) {
        return "task".equalsIgnoreCase(workItemType == null ? "" : workItemType.trim()) ? "task" : "bug";
    }

    static String prefixFor(String kind) {
        return "task".equals(kind) ? TASK_PREFIX : BUG_PREFIX;
    }

    /** The branch family a prefix belongs to: "fix/us-" -> "fix/", "feature/us-" -> "feature/". */
    static String familyOf(String prefix) {
        int slash = prefix.indexOf('/');
        return slash >= 0 ? prefix.substring(0, slash + 1) : prefix;
    }
    /** Base for newly created story branches; empty = the remote's default branch (origin/HEAD), else HEAD. */
    static final String BASE_BRANCH = opt("BUG_AGENT_BASE_BRANCH", "UAT");
    /** Merged into every newly created branch right after it is cut from BASE_BRANCH; empty = no sync. */
    static final String SYNC_BRANCH = opt("BUG_AGENT_SYNC_BRANCH", "master");
    /** worktree = separate folder per branch (developer's checkout untouched); checkout = switch the project folder itself. */
    static final String BRANCH_MODE = opt("BUG_AGENT_BRANCH_MODE", "worktree").toLowerCase();
    static final Path STATE_FILE = DATA_DIR.resolve("state.json");
    static final Path PROJECTS_FILE = DATA_DIR.resolve("projects.json");
    static final Path WORKDIRS_FILE = DATA_DIR.resolve("workdirs.json");
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
                "List work items (Bugs and Tasks by default; each has a `type`) assigned to me in the configured states (default New) that bug-agent has not handled yet, with title, link, priority, description, repro steps, "
                        + "and the parent user story (title, description, acceptance criteria) found by walking up the bug's parent links. "
                        + "Bugs whose last attempt errored are retried up to the attempt limit.",
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

        tool("get_bug", "Get one bug's details, link and parent user story (with acceptance criteria) by id.",
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
                "Put one project on the right branch for a work item (Bug or Task) and return the folder the fixer edits. "
                        + "Git projects with userStoryId: Bugs use an existing " + familyOf(BUG_PREFIX) + "... branch containing the story number, "
                        + "Tasks an existing " + familyOf(TASK_PREFIX) + "... one (local first, then remote after a fetch); otherwise a new branch "
                        + BUG_PREFIX + "<storyId> (Bug) or " + TASK_PREFIX + "<storyId> (Task) is created from the default branch. "
                        + "All bugs of a story share its fix branch, all tasks its feature branch. Without a story: "
                        + familyOf(BUG_PREFIX) + "bug-<id> or " + familyOf(TASK_PREFIX) + "task-<id>. Branch mode '" + BRANCH_MODE
                        + "': worktree = separate folder, the developer's checkout is untouched; checkout = switches the project folder itself "
                        + "(refused if it has uncommitted changes). Non-git projects: an overlay folder pre-filled with copies of `files`.",
                """
                {"type":"object","properties":{"bugId":{"type":"integer","description":"The work item id (bug or task)"},"project":{"type":"string"},
                 "itemType":{"type":"string","enum":["Bug","Task"],"default":"Bug","description":"The work item's type; decides fix/ or feature/ branches"},
                 "userStoryId":{"type":"integer","description":"Id of the item's parent user story, if it has one"},
                 "files":{"type":"array","items":{"type":"string"},"description":"Project-relative files the plan touches"}},
                 "required":["bugId","project"],"additionalProperties":false}
                """, a -> prepareWorkdir((long) num(a.get("bugId"), -1), String.valueOf(a.get("project")),
                        a.get("files") == null ? List.of() : asList(a.get("files")).stream().map(String::valueOf).collect(Collectors.toList()),
                        a.get("userStoryId") == null ? null : (long) num(a.get("userStoryId"), -1),
                        kindOf(a.get("itemType") == null ? "Bug" : String.valueOf(a.get("itemType")))));

        tool("finalize_workdir",
                "After the fixer finishes: commit the changed files on the bug's branch (one commit per bug, mentioning the user story), "
                        + "or prune unchanged copies and write _FIX_NOTES.md (overlay). Returns filesChanged, branch, commit.",
                """
                {"type":"object","properties":{"bugId":{"type":"integer"},"project":{"type":"string"},"title":{"type":"string"},
                 "url":{"type":"string"},"rootCause":{"type":"string"},"summary":{"type":"string"},"userStoryTitle":{"type":"string"}},
                 "required":["bugId","project"],"additionalProperties":false}
                """, AdoMcp::finalizeWorkdir);

        tool("record_bug", "Remember the outcome for a bug so later cycles skip it (errors are retried up to the attempt limit).",
                """
                {"type":"object","properties":{"id":{"type":"integer"},"title":{"type":"string"},"url":{"type":"string"},
                 "outcome":{"type":"string","enum":["fixed","partially_fixed","needs_human","error"]},
                 "rootCause":{"type":"string"},"confidence":{"type":"string"},"notes":{"type":"string"},
                 "userStoryId":{"type":"integer"},"userStoryTitle":{"type":"string"},"itemType":{"type":"string"},
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
        String types = WORK_ITEM_TYPES.stream().map(q).collect(Collectors.joining(", "));
        return "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = " + q.apply(project) + " "
                + "AND [System.WorkItemType] IN (" + types + ") AND [System.AssignedTo] = " + who + " "
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

    /** Raw work items (all fields + links), keyed by id. $expand cannot be combined with a fields list. */
    static Map<Long, Map<String, Object>> fetchRaw(Collection<Long> ids) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        List<Long> list = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < list.size(); i += 200) {
            String idList = list.subList(i, Math.min(list.size(), i + 200)).stream().map(String::valueOf).collect(Collectors.joining(","));
            Object data = ado("GET", orgUrl() + "/_apis/wit/workitems?ids=" + idList + "&$expand=relations&errorPolicy=omit&api-version=7.1", null);
            for (Object w : asList(asMap(data).get("value"))) {
                if (w == null) continue;
                Map<String, Object> wi = asMap(w);
                out.put((long) num(wi.get("id"), -1), wi);
            }
        }
        return out;
    }

    /** Parent id from the work item's hierarchy link, or null. */
    static Long parentOf(Map<String, Object> wi) {
        for (Object r : asList(wi.get("relations"))) {
            Map<String, Object> rel = asMap(r);
            if (!"System.LinkTypes.Hierarchy-Reverse".equals(rel.get("rel"))) continue;
            String url = str(rel.get("url"));
            try {
                return Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String typeOf(Map<String, Object> wi) {
        return str(asMap(wi.get("fields")).get("System.WorkItemType"));
    }

    /**
     * Walks up from a bug's parent to the first User Story / Product Backlog Item / Requirement
     * (at most 4 levels, e.g. Bug -> Task -> User Story). Results are cached per call.
     */
    static Map<String, Object> resolveStory(Long parentId, Map<Long, Map<String, Object>> cache) {
        List<String> via = new ArrayList<>();
        Long cur = parentId;
        for (int depth = 0; cur != null && depth < 4; depth++) {
            if (!cache.containsKey(cur)) cache.putAll(fetchRaw(List.of(cur)));
            Map<String, Object> wi = cache.get(cur);
            if (wi == null) return map("found", false, "note", "parent #" + cur + " could not be read (deleted, or no permission)");
            String type = typeOf(wi);
            if (STORY_TYPES.contains(type.toLowerCase())) {
                Map<String, Object> f = asMap(wi.get("fields"));
                return map(
                        "found", true,
                        "id", cur,
                        "type", type,
                        "title", str(f.get("System.Title")),
                        "state", str(f.get("System.State")),
                        "url", bugLink(cur),
                        "via", via,
                        "description", cut(stripHtml(str(f.get("System.Description"))), 6000),
                        "acceptanceCriteria", cut(stripHtml(str(f.get("Microsoft.VSTS.Common.AcceptanceCriteria"))), 6000));
            }
            via.add(type + " #" + cur);
            cur = parentOf(wi);
        }
        String path = via.isEmpty() ? "" : " (checked " + String.join(" -> ", via) + ")";
        return map("found", false, "note", (parentId == null ? "the bug has no parent" : "no " + String.join(" / ", STORY_TYPES) + " above the bug") + path);
    }

    static List<Object> getItems(List<Long> ids) {
        if (ids.isEmpty()) return new ArrayList<>();
        Map<Long, Map<String, Object>> raw = fetchRaw(ids);
        Map<Long, Map<String, Object>> cache = new HashMap<>(raw);
        List<Object> out = new ArrayList<>();
        for (Long id : ids) { // keep the WIQL priority order
            Map<String, Object> wi = raw.get(id);
            if (wi == null) continue;
            Map<String, Object> f = asMap(wi.get("fields"));
            Long parent = parentOf(wi);
            out.add(map(
                    "id", id,
                    "type", str(f.get("System.WorkItemType")),
                    "title", str(f.get("System.Title")),
                    "url", bugLink(id),
                    "state", str(f.get("System.State")),
                    "priority", f.get("Microsoft.VSTS.Common.Priority"),
                    "severity", f.get("Microsoft.VSTS.Common.Severity"),
                    "areaPath", str(f.get("System.AreaPath")),
                    "tags", str(f.get("System.Tags")),
                    "description", cut(stripHtml(str(f.get("System.Description"))), 4000),
                    "reproSteps", cut(stripHtml(str(f.get("Microsoft.VSTS.TCM.ReproSteps"))), 4000),
                    "parentId", parent,
                    "userStory", resolveStory(parent, cache)));
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

    // ---- story branches -------------------------------------------------------------

    /** A branch "has the story number" when the number appears with no digit directly before or after it. */
    static boolean branchHasNumber(String branch, long storyId) {
        return Pattern.compile("(?<![0-9])" + storyId + "(?![0-9])").matcher(branch).find();
    }

    /** Branch of the right type for this story: in the prefix's family (fix/ or feature/) and containing the story number. */
    static boolean storyBranchMatches(String branch, long storyId, String prefix) {
        return branch.toLowerCase().startsWith(familyOf(prefix).toLowerCase()) && branchHasNumber(branch, storyId);
    }

    /**
     * Finds the branch for a user story and work item type: local branches first, then remote-tracking
     * branches, only in the type's family (bugs: fix/..., tasks: feature/...). Among several matches it
     * prefers prefix+id exactly, then the most recently committed one.
     */
    static Map<String, Object> findStoryBranch(Path repo, long storyId, String prefix) {
        List<String[]> local = new ArrayList<>(), remote = new ArrayList<>();
        for (String l : lines(git(repo, "for-each-ref", "--format=%(refname)|%(committerdate:unix)", "refs/heads", "refs/remotes"))) {
            String[] parts = l.split("\\|", 2);
            String ref = parts[0];
            String ts = parts.length > 1 ? parts[1] : "0";
            if (ref.startsWith("refs/heads/")) {
                String name = ref.substring("refs/heads/".length());
                if (storyBranchMatches(name, storyId, prefix)) local.add(new String[] {name, ts});
            } else if (ref.startsWith("refs/remotes/") && !ref.endsWith("/HEAD")) {
                String name = ref.substring("refs/remotes/".length()); // origin/fix/us-123
                if (storyBranchMatches(name.substring(name.indexOf('/') + 1), storyId, prefix)) remote.add(new String[] {name, ts});
            }
        }
        String preferred = prefix + storyId;
        Comparator<String[]> best = Comparator
                .comparing((String[] b) -> !(b[0].equalsIgnoreCase(preferred) || b[0].toLowerCase().endsWith("/" + preferred.toLowerCase())))
                .thenComparing((String[] b) -> -Long.parseLong(b[1].isBlank() ? "0" : b[1]));
        local.sort(best);
        remote.sort(best);
        List<String> all = new ArrayList<>();
        local.forEach(b -> all.add(b[0]));
        remote.forEach(b -> all.add(b[0]));
        if (!local.isEmpty()) return map("kind", "local", "branch", local.get(0)[0], "matches", all);
        if (!remote.isEmpty()) {
            String r = remote.get(0)[0];
            return map("kind", "remote", "branch", r.substring(r.indexOf('/') + 1), "remoteRef", r, "matches", all);
        }
        return null;
    }

    /**
     * Brings a just-created branch up to date with SYNC_BRANCH (origin/master preferred) by merging it in.
     * Only the new branch changes; the base and sync branches are untouched. On a conflict the merge is
     * aborted, the new branch is removed again, and the error explains it. Returns the ref merged, or null if none exists.
     */
    static String syncNewBranch(Path top, Path root, String branch, String mode) {
        if (SYNC_BRANCH.isEmpty()) return null;
        String ref = refExists(top, "refs/remotes/origin/" + SYNC_BRANCH) ? "origin/" + SYNC_BRANCH
                : refExists(top, "refs/heads/" + SYNC_BRANCH) ? SYNC_BRANCH : null;
        if (ref == null) return null;
        try {
            git(root, "-c", "user.name=bug-agent", "-c", "user.email=bug-agent@localhost", "merge", "--no-edit", ref);
            return ref;
        } catch (RuntimeException e) {
            try { git(root, "merge", "--abort"); } catch (RuntimeException ignored) {}
            try {
                if ("worktree".equals(mode)) git(top, "worktree", "remove", "--force", root.toString());
                else git(top, "checkout", "-");
                git(top, "branch", "-D", branch);
            } catch (RuntimeException ignored) {}
            throw new IllegalStateException("new branch " + branch + " was cut from " + baseRef(top) + " but merging " + ref
                    + " into it conflicts, so it was removed again. Sync " + BASE_BRANCH + " with " + SYNC_BRANCH + " by hand, then run the cycle again.");
        }
    }

    /** Base for a new branch: BASE_BRANCH (preferring origin/BASE), else origin/HEAD, else HEAD. */
    static String baseRef(Path repo) {
        if (!BASE_BRANCH.isEmpty()) {
            if (refExists(repo, "refs/remotes/origin/" + BASE_BRANCH)) return "origin/" + BASE_BRANCH;
            if (refExists(repo, "refs/heads/" + BASE_BRANCH)) return BASE_BRANCH;
            // no UAT in this repository: fall through to its default branch (the result's branchSource names the base used)
        }
        try {
            return git(repo, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        } catch (RuntimeException e) {
            return "HEAD";
        }
    }

    static boolean refExists(Path repo, String ref) {
        try {
            git(repo, "show-ref", "--verify", "--quiet", ref);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Folder where `branch` is currently checked out (main checkout or a worktree), or null. */
    static Path checkedOutAt(Path repo, String branch) {
        Path cur = null;
        for (String l : lines(git(repo, "worktree", "list", "--porcelain"))) {
            if (l.startsWith("worktree ")) cur = Paths.get(l.substring(9)).toAbsolutePath().normalize();
            else if (l.equals("branch refs/heads/" + branch)) return cur;
        }
        return null;
    }

    static boolean hasTrackedChanges(Path dir) {
        return !git(dir, "status", "--porcelain", "--untracked-files=no").isEmpty();
    }

    /** Best effort: refresh remote branches so an existing story branch on the server is found. Never prompts. */
    static String tryFetch(Path repo) {
        if (git(repo, "remote").isEmpty()) return null;
        try {
            gitTimed(repo, 60, "fetch", "--all", "--prune", "--quiet");
            return null;
        } catch (RuntimeException e) {
            return "git fetch failed, using the branches already known locally: " + e.getMessage();
        }
    }

    /**
     * Gets a git project onto the right branch for this work item and returns where the fixer edits.
     * With a user story: an existing branch of the item's type containing the story number, or a new
     * prefix+storyId branch (bugs: fix/us-<story>, tasks: feature/us-<story>).
     * Without one: fix/bug-<id> or feature/task-<id>.
     */
    static Map<String, Object> prepareGit(Path top, Path src, long bugId, Long storyId, String kind) throws IOException {
        Path sub = top.relativize(src);
        String prefix = prefixFor(kind);
        String fetchWarning = tryFetch(top); // latest remote branches, UAT and master
        Map<String, Object> found = storyId != null ? findStoryBranch(top, storyId, prefix) : null;
        String branch;
        String source;
        List<String> createArgs; // args after "worktree add <dir>" or "checkout"
        if (found != null && "local".equals(found.get("kind"))) {
            branch = String.valueOf(found.get("branch"));
            source = "existing local branch";
            createArgs = List.of(branch);
        } else if (found != null) {
            branch = String.valueOf(found.get("branch"));
            source = "existing remote branch " + found.get("remoteRef");
            createArgs = List.of("--track", "-b", branch, String.valueOf(found.get("remoteRef")));
        } else {
            branch = storyId != null ? prefix + storyId : familyOf(prefix) + kind + "-" + bugId;
            if (storyId == null && refExists(top, "refs/heads/" + branch)) {
                source = "existing local branch"; // a retry of the same work item
                createArgs = List.of(branch);
            } else {
                String base = baseRef(top);
                source = "new branch from " + base;
                createArgs = List.of("-b", branch, base);
            }
        }

        Path root;
        String mode;
        Path already = refExists(top, "refs/heads/" + branch) ? checkedOutAt(top, branch) : null;
        if (already != null) {
            // The branch is already open in a folder: work there, as long as nothing uncommitted would get mixed in.
            if (hasTrackedChanges(already) && !already.startsWith(DATA_DIR))
                throw new IllegalStateException("branch " + branch + " is checked out at " + already
                        + " with uncommitted changes; commit or stash them, then run the cycle again");
            root = already;
            mode = already.startsWith(DATA_DIR) ? "worktree" : "checkout";
            source = source + " (already checked out at " + already + ")";
        } else if ("checkout".equals(BRANCH_MODE)) {
            if (hasTrackedChanges(top))
                throw new IllegalStateException(top + " has uncommitted changes; commit or stash them before bug-agent switches it to " + branch);
            List<String> args = new ArrayList<>(List.of("checkout"));
            args.addAll(createArgs);
            git(top, args.toArray(new String[0]));
            root = top;
            mode = "checkout";
        } else {
            String key = storyId != null ? "story-" + storyId + "-" + kind : kind + "-" + bugId; // bug and task work of a story use different branches
            root = DATA_DIR.resolve("worktrees").resolve(safeName(top.getFileName().toString()) + "-" + key);
            Files.createDirectories(root.getParent());
            List<String> args = new ArrayList<>(List.of("worktree", "add"));
            if (createArgs.size() == 1) { args.add(root.toString()); args.add(createArgs.get(0)); }
            else { args.addAll(createArgs.subList(0, createArgs.size() - 1)); args.add(root.toString()); args.add(createArgs.get(createArgs.size() - 1)); }
            git(top, args.toArray(new String[0]));
            mode = "worktree";
        }
        String synced = null;
        if (already == null && source.startsWith("new branch")) {
            synced = syncNewBranch(top, root, branch, mode);
            if (synced != null) source = source + ", synced with " + synced;
        }
        Map<String, Object> out = map("mode", mode, "workdir", root.resolve(sub).normalize().toString(), "branch", branch,
                "branchSource", source, "sourcePath", src.toString());
        if (synced == null && !SYNC_BRANCH.isEmpty() && already == null && source.startsWith("new branch"))
            out.put("syncWarning", "sync branch '" + SYNC_BRANCH + "' not found; the new branch was not synced");
        out.put("untrackedBefore", lines(git(root, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard")));
        if (storyId != null) out.put("userStoryId", storyId);
        out.put("kind", kind);
        if (found != null && asList(found.get("matches")).size() > 1) out.put("otherMatchingBranches", asList(found.get("matches")).subList(1, asList(found.get("matches")).size()));
        if (fetchWarning != null) out.put("warning", fetchWarning);
        return out;
    }

    static Map<String, Object> prepareWorkdir(long bugId, String project, List<String> files, Long storyId, String kind) {
        Map<String, Object> p = projectByName(project);
        Path src = Paths.get(String.valueOf(p.get("path")));
        String mode = String.valueOf(p.getOrDefault("applyMode", "auto"));
        Path top = "overlay".equals(mode) ? null : gitTop(src);
        if ("worktree".equals(mode) && top == null) throw new IllegalArgumentException(src + " is not a git repository");
        try {
            Map<String, Object> w;
            if (top != null) {
                w = prepareGit(top, src, bugId, storyId, kind);
            } else {
                Path overlay = DATA_DIR.resolve("patches");
                if (storyId != null) overlay = overlay.resolve("story-" + storyId);
                overlay = overlay.resolve(kind + "-" + bugId).resolve(safeName(project));
                for (String rel : files) {
                    Path from = inside(src, rel);
                    Path to = inside(overlay, rel);
                    if (Files.exists(from) && !Files.exists(to)) {
                        Files.createDirectories(to.getParent());
                        Files.copy(from, to);
                    }
                }
                Files.createDirectories(overlay);
                w = map("mode", "overlay", "workdir", overlay.toString(), "sourcePath", src.toString());
                if (storyId != null) w.put("userStoryId", storyId);
                w.put("kind", kind);
            }
            // Remember it so finalize_workdir commits in exactly this place.
            Object saved = readJson(WORKDIRS_FILE);
            Map<String, Object> all = saved instanceof Map ? asMap(saved) : new LinkedHashMap<>();
            all.put(bugId + "|" + project, w);
            writeJson(WORKDIRS_FILE, all);
            Map<String, Object> ret = new LinkedHashMap<>(w);
            ret.remove("untrackedBefore"); // internal bookkeeping, not for the model
            return ret;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Object finalizeWorkdir(Map<String, Object> a) {
        long bugId = (long) num(a.get("bugId"), -1);
        String project = String.valueOf(a.get("project"));
        String title = strOr(a.get("title")), url = strOr(a.get("url")), rootCause = strOr(a.get("rootCause")), summary = strOr(a.get("summary"));
        String storyTitle = strOr(a.get("userStoryTitle"));
        Map<String, Object> p = projectByName(project);
        Object saved = readJson(WORKDIRS_FILE);
        Object entry = saved instanceof Map ? asMap(saved).get(bugId + "|" + project) : null;
        if (!(entry instanceof Map)) throw new IllegalStateException("call prepare_workdir for bug " + bugId + " in " + project + " first");
        Map<String, Object> w = asMap(entry);
        Object storyId = w.get("userStoryId");
        boolean task = "task".equals(w.get("kind"));
        String itemLabel = (task ? "task #" : "bug #") + bugId;
        Path workdir = Paths.get(String.valueOf(w.get("workdir")));
        try {
            if (!"overlay".equals(w.get("mode"))) {
                Path wtRoot = gitTop(workdir);
                String sub = wtRoot.relativize(workdir).toString().replace('\\', '/');
                String scope = sub.isEmpty() ? "." : sub;
                LinkedHashSet<String> changed = new LinkedHashSet<>();
                changed.addAll(lines(git(wtRoot, "-c", "core.quotepath=false", "diff", "--name-only", "HEAD", "--", scope)));
                Set<String> before = new HashSet<>();
                for (Object o : asList(w.get("untrackedBefore"))) before.add(String.valueOf(o));
                for (String u : lines(git(wtRoot, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard", "--", scope)))
                    if (!before.contains(u)) changed.add(u); // never sweep the developer's own untracked files into the commit
                Map<String, Object> out = map("mode", w.get("mode"), "workdir", workdir.toString(), "branch", w.get("branch"));
                if (storyId != null) out.put("userStoryId", storyId);
                if (changed.isEmpty()) {
                    out.put("filesChanged", List.of());
                    out.put("commit", null);
                    return out;
                }
                List<String> add = new ArrayList<>(List.of("add", "--"));
                add.addAll(changed);
                git(wtRoot, add.toArray(new String[0]));
                String story = storyId == null ? "" : "\n\nUser story #" + storyId + (storyTitle.isEmpty() ? "" : ": " + storyTitle);
                String msg = ((task ? "feat: " : "fix: ") + itemLabel + " " + title + story + "\n\n" + rootCause + "\n\n" + summary + "\n\n" + url).trim();
                git(wtRoot, "-c", "user.name=bug-agent", "-c", "user.email=bug-agent@localhost", "commit", "-m", msg);
                out.put("commit", git(wtRoot, "rev-parse", "--short", "HEAD"));
                out.put("filesChanged", new ArrayList<>(changed));
                return out;
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
                String notes = "# " + (task ? "Task #" : "Bug #") + bugId + ": " + title + "\n\n" + url
                        + (storyId == null ? "" : "\n\nUser story #" + storyId + (storyTitle.isEmpty() ? "" : ": " + storyTitle))
                        + "\n\nCopy these files into " + src + " after review.\n\n"
                        + "## Root cause\n" + rootCause + "\n\n## Changes\n" + summary + "\n\n## Files\n"
                        + changed.stream().map(f -> "- " + f).collect(Collectors.joining("\n")) + "\n";
                Files.writeString(workdir.resolve("_FIX_NOTES.md"), notes, StandardCharsets.UTF_8);
            }
            Map<String, Object> out = map("mode", "overlay", "workdir", workdir.toString(), "filesChanged", changed);
            if (storyId != null) out.put("userStoryId", storyId);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ git / files
    static String git(Path cwd, String... args) {
        return gitTimed(cwd, 0, args);
    }

    /** Runs git (never interactive). timeoutSeconds 0 = no limit. */
    static String gitTimed(Path cwd, int timeoutSeconds, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // fail instead of waiting for a password
            File errFile = File.createTempFile("bug-agent-git", ".err");
            pb.redirectError(errFile);
            Process proc = pb.start();
            byte[] out;
            if (timeoutSeconds > 0) {
                if (!proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    errFile.delete();
                    throw new IllegalStateException("git " + args[0] + " timed out after " + timeoutSeconds + "s");
                }
                out = proc.getInputStream().readAllBytes();
            } else {
                out = proc.getInputStream().readAllBytes();
                proc.waitFor();
            }
            String err = Files.readString(errFile.toPath(), StandardCharsets.UTF_8).trim();
            errFile.delete();
            if (proc.exitValue() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + err);
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
