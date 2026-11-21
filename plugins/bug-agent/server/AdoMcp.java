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
    /** Optional override for the base of new branches; empty (default) = each repository's own root branch (main, master, ...). */
    static final String BASE_BRANCH = opt("BUG_AGENT_BASE_BRANCH", "");
    /** Optional branch merged into every new branch right after it is cut; empty (default) = no sync (the base already is the root branch). */
    static final String SYNC_BRANCH = opt("BUG_AGENT_SYNC_BRANCH", "");
    /** checkout = switch the project folder itself (live in the IDE, default); worktree = separate folder per branch. */
    static final String BRANCH_MODE = opt("BUG_AGENT_BRANCH_MODE", "checkout").toLowerCase();
    /** false (default) = leave every change uncommitted for the developer to review and commit; true = one commit per item. */
    static final boolean AUTO_COMMIT = List.of("true", "1", "yes").contains(opt("BUG_AGENT_AUTO_COMMIT", "false").toLowerCase());
    /** Minutes after a story's work is finished before the review-status notification is shown. */
    static final double REVIEW_ALERT_MINUTES = parseDouble(opt("BUG_AGENT_REVIEW_ALERT_MINUTES", "15"), 15);
    /** os = desktop notification (+ log); log = only alerts.log (tests, servers). */
    static final String NOTIFY = opt("BUG_AGENT_NOTIFY", "os").toLowerCase();
    static final Path STATE_FILE = DATA_DIR.resolve("state.json");
    static final Path PROJECTS_FILE = DATA_DIR.resolve("projects.json");
    static final Path WORKDIRS_FILE = DATA_DIR.resolve("workdirs.json");
    static final Path UNITS_FILE = DATA_DIR.resolve("units.json");
    static final Path ALERTS_LOG = DATA_DIR.resolve("alerts.log");
    /** Reports and patches live outside the projects. */
    static final Path REPORT_DIR = Paths.get(opt("BUG_AGENT_REPORT_DIR", DATA_DIR.resolve("reports").toString())).toAbsolutePath().normalize();

    static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
    static final HttpClient HTTP = HttpClient.newHttpClient();

    // ------------------------------------------------------------------ entry point
    public static void main(String[] args) throws IOException {
        Files.createDirectories(DATA_DIR);
        if (args.length >= 2 && "--alert".equals(args[0])) { // detached: wait, then tell the developer the review status
            runAlert(args[1]);
            return;
        }
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
                        + "(refused if it has uncommitted changes that aren't this unit's). Non-git projects: an overlay folder pre-filled with copies of `files`. "
                        + "Pass the unitId from start_unit so all items of the story share the branch and their uncommitted changes.",
                """
                {"type":"object","properties":{"bugId":{"type":"integer","description":"The work item id (bug or task)"},"project":{"type":"string"},
                 "itemType":{"type":"string","enum":["Bug","Task"],"default":"Bug","description":"The work item's type; decides fix/ or feature/ branches"},
                 "userStoryId":{"type":"integer","description":"Id of the item's parent user story, if it has one"},
                 "unitId":{"type":"string","description":"The review unit from start_unit"},
                 "files":{"type":"array","items":{"type":"string"},"description":"Project-relative files the plan touches"}},
                 "required":["bugId","project"],"additionalProperties":false}
                """, a -> prepareWorkdir((long) num(a.get("bugId"), -1), String.valueOf(a.get("project")),
                        a.get("files") == null ? List.of() : asList(a.get("files")).stream().map(String::valueOf).collect(Collectors.toList()),
                        a.get("userStoryId") == null ? null : (long) num(a.get("userStoryId"), -1),
                        kindOf(a.get("itemType") == null ? "Bug" : String.valueOf(a.get("itemType"))),
                        a.get("unitId") == null ? null : String.valueOf(a.get("unitId"))));

        tool("finalize_workdir",
                "After the fixer finishes: commit the changed files on the bug's branch (one commit per bug, mentioning the user story), "
                        + "or prune unchanged copies and write _FIX_NOTES.md (overlay). Returns filesChanged, branch, commit.",
                """
                {"type":"object","properties":{"bugId":{"type":"integer"},"project":{"type":"string"},"title":{"type":"string"},
                 "url":{"type":"string"},"rootCause":{"type":"string"},"summary":{"type":"string"},"userStoryTitle":{"type":"string"}},
                 "required":["bugId","project"],"additionalProperties":false}
                """, AdoMcp::finalizeWorkdir);

        tool("review_gate",
                "Call first in every cycle. Shows the user-story work still waiting for the developer's review (uncommitted, or staged but not "
                        + "committed) per project folder and branch, and marks work as reviewed once the folder is clean again (committed or discarded). "
                        + "canStartNewWork is false while anything waits for review.",
                empty, a -> reviewGate());

        tool("start_unit",
                "Start this cycle's unit of work: one user story and one type (all its Bugs on the fix branch, or all its Tasks on the feature branch). "
                        + "Refused while earlier work waits for review. Returns unitId for prepare_workdir, unit_patch and finish_unit.",
                """
                {"type":"object","properties":{"userStoryId":{"type":"integer"},"userStoryTitle":{"type":"string"},
                 "itemType":{"type":"string","enum":["Bug","Task"]},
                 "items":{"type":"array","items":{"type":"object"},"description":"The work items in this unit: id, title, url"}},
                 "required":["itemType"],"additionalProperties":false}
                """, AdoMcp::startUnit);

        tool("unit_patch",
                "Write the unit's full diff (every change since the branch was prepared, including new files) to <report dir>/<unitId>.patch "
                        + "outside the projects, for the reviewer agent to read. Returns patchPath.",
                """
                {"type":"object","properties":{"unitId":{"type":"string"}},"required":["unitId"],"additionalProperties":false}
                """, a -> {
                    Object u = loadUnits().get(String.valueOf(a.get("unitId")));
                    if (!(u instanceof Map)) throw new IllegalArgumentException("unknown unitId " + a.get("unitId"));
                    return writeUnitPatch(asMap(u));
                });

        tool("finish_unit",
                "Finish the unit: save the report (your markdown plus where the changes are) and the patch in the report folder outside the "
                        + "projects, leave the changes uncommitted for review, and schedule the review-status desktop notification in "
                        + REVIEW_ALERT_MINUTES + " minutes (ready for staging or not, and whether the developer has reviewed yet).",
                """
                {"type":"object","properties":{"unitId":{"type":"string"},
                 "readyForStaging":{"type":"boolean","description":"The reviewer agent's verdict"},
                 "verdictSummary":{"type":"string"},
                 "issues":{"type":"array","items":{"type":"string"}},
                 "reportMarkdown":{"type":"string","description":"The report: what changed per item and why, acceptance criteria coverage, review findings, how to test"}},
                 "required":["unitId","readyForStaging","reportMarkdown"],"additionalProperties":false}
                """, AdoMcp::finishUnit);

        tool("review_branches",
                "List every branch bug-agent created or used (fix/... for bugs, feature/... for tasks), grouped by branch with its user story, "
                        + "the work items committed on it (id, type, title, link, outcome, commit) and, per project, whether the branch exists, "
                        + "where it is checked out, which branch the project folder is on, and the commits and files bug-agent added since the base branch. "
                        + "Also lists overlay patch folders for non-git projects.",
                """
                {"type":"object","properties":{"userStoryId":{"type":"integer","description":"Only branches of this user story"}},"additionalProperties":false}
                """, AdoMcp::reviewBranches);

        tool("checkout_branch",
                "Check a branch out in the developer's own project folders so they can review the changes: by default every project the branch "
                        + "was used in (or the projects named). Frees the branch from a bug-agent worktree first; never touches a project with "
                        + "uncommitted changes. Also works for switching back, e.g. to UAT. Returns, per project, what happened, the previous branch, "
                        + "and the commits and files on the branch since the base branch.",
                """
                {"type":"object","properties":{"branch":{"type":"string"},
                 "projects":{"type":"array","items":{"type":"string"},"description":"Project names; default = projects the branch was used in, else every project that has it"}},
                 "required":["branch"],"additionalProperties":false}
                """, AdoMcp::checkoutBranch);

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
            String base = baseRef(top);
            throw new IllegalStateException("new branch " + branch + " was cut from " + base + " but merging " + ref
                    + " into it conflicts, so it was removed again. Sync " + base + " with " + ref + " by hand, then run the cycle again.");
        }
    }

    /**
     * Base for a new branch: the BASE_BRANCH override if set and present (origin/ preferred), otherwise the
     * repository's root branch, whatever it is called (main, master, trunk, develop...).
     */
    static String baseRef(Path repo) {
        if (!BASE_BRANCH.isEmpty()) {
            if (refExists(repo, "refs/remotes/origin/" + BASE_BRANCH)) return "origin/" + BASE_BRANCH;
            if (refExists(repo, "refs/heads/" + BASE_BRANCH)) return BASE_BRANCH;
            // override not in this repository: fall through to its root branch (branchSource names the base used)
        }
        return rootBranch(repo);
    }

    /**
     * The repository's root (default) branch, detected rather than assumed:
     * 1. origin/HEAD as recorded by clone; 2. the remote's HEAD asked live (ls-remote --symref);
     * 3. a remote or local main / master; 4. the branch HEAD points at (repositories without a remote).
     */
    static String rootBranch(Path repo) {
        try {
            return git(repo, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        } catch (RuntimeException ignored) {}
        if (!git(repo, "remote").isEmpty()) {
            try {
                for (String l : lines(gitTimed(repo, 20, "ls-remote", "--symref", "origin", "HEAD"))) {
                    java.util.regex.Matcher m = Pattern.compile("^ref: refs/heads/(\\S+)\\s+HEAD$").matcher(l.trim());
                    if (m.find() && refExists(repo, "refs/remotes/origin/" + m.group(1))) return "origin/" + m.group(1);
                }
            } catch (RuntimeException ignored) {} // offline: use what we know locally
        }
        for (String name : List.of("main", "master")) {
            if (refExists(repo, "refs/remotes/origin/" + name)) return "origin/" + name;
        }
        for (String name : List.of("main", "master")) {
            if (refExists(repo, "refs/heads/" + name)) return name;
        }
        return "HEAD";
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

    /** True when `root` is already part of this review unit on `branch`, so its uncommitted changes are the unit's own. */
    static boolean unitOwns(Map<String, Object> unit, Path root, String branch) {
        if (unit == null) return false;
        Object p = asMap(unit.get("repos")).get(root.toString());
        return p instanceof Map && branch.equals(asMap(p).get("branch"));
    }

    /** path -> content hash of every file that differs from HEAD or is untracked (minus `exclude`). */
    static Map<String, Object> changedHashes(Path root, Set<String> exclude) {
        Map<String, Object> m = new TreeMap<>();
        List<String> files = new ArrayList<>(lines(git(root, "-c", "core.quotepath=false", "diff", "--name-only", "HEAD")));
        for (String u : lines(git(root, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard")))
            if (!exclude.contains(u)) files.add(u);
        for (String f : files) {
            Path p = root.resolve(f);
            m.put(f, Files.exists(p) ? git(root, "hash-object", "--", f) : "deleted");
        }
        return m;
    }

    /**
     * Gets a git project onto the right branch for this work item and returns where the fixer edits.
     * With a user story: an existing branch of the item's type containing the story number, or a new
     * prefix+storyId branch (bugs: fix/us-<story>, tasks: feature/us-<story>).
     * Without one: fix/bug-<id> or feature/task-<id>.
     */
    static Map<String, Object> prepareGit(Path top, Path src, long bugId, Long storyId, String kind, Map<String, Object> unit) throws IOException {
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
            if (hasTrackedChanges(already) && !already.startsWith(DATA_DIR) && !unitOwns(unit, already, branch))
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
            // repo folder name + short hash of its path: two repositories can share a folder name
            String repoKey = safeName(top.getFileName().toString()) + "-" + Integer.toHexString(top.toString().toLowerCase().hashCode());
            root = DATA_DIR.resolve("worktrees").resolve(repoKey + "-" + key);
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
        // Baseline for this review unit: where the branch stood and which untracked files were the developer's.
        List<String> untrackedNow = lines(git(root, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"));
        List<Object> baselineUntracked = new ArrayList<>(untrackedNow);
        if (unit != null) {
            Map<String, Object> repos = asMap(unit.get("repos"));
            Object known = repos.get(root.toString());
            if (known instanceof Map) baselineUntracked = asList(asMap(known).get("baselineUntracked"));
            else repos.put(root.toString(), map("branch", branch, "mode", mode, "folder", root.toString(),
                    "baselineHead", git(root, "rev-parse", "HEAD"), "baselineUntracked", untrackedNow, "projects", new ArrayList<>()));
            List<Object> projects = asList(asMap(repos.get(root.toString())).get("projects"));
            if (!projects.contains(src.toString())) projects.add(src.toString());
        }
        out.put("untrackedBefore", baselineUntracked);
        Set<String> exclude = new HashSet<>();
        for (Object o : baselineUntracked) exclude.add(String.valueOf(o));
        out.put("snapshot", changedHashes(root, exclude)); // what was already changed before this item
        if (storyId != null) out.put("userStoryId", storyId);
        out.put("kind", kind);
        if (found != null && asList(found.get("matches")).size() > 1) out.put("otherMatchingBranches", asList(found.get("matches")).subList(1, asList(found.get("matches")).size()));
        if (fetchWarning != null) out.put("warning", fetchWarning);
        return out;
    }

    static Map<String, Object> prepareWorkdir(long bugId, String project, List<String> files, Long storyId, String kind, String unitId) {
        Map<String, Object> p = projectByName(project);
        Path src = Paths.get(String.valueOf(p.get("path")));
        String mode = String.valueOf(p.getOrDefault("applyMode", "auto"));
        Path top = "overlay".equals(mode) ? null : gitTop(src);
        if ("worktree".equals(mode) && top == null) throw new IllegalArgumentException(src + " is not a git repository");
        Map<String, Object> units = loadUnits();
        Map<String, Object> unit = null;
        if (unitId != null) {
            Object u = units.get(unitId);
            if (!(u instanceof Map)) throw new IllegalArgumentException("unknown unitId " + unitId + "; call start_unit first");
            unit = asMap(u);
            if (!"in_progress".equals(unit.get("status"))) throw new IllegalStateException("unit " + unitId + " is " + unit.get("status") + ", not in progress");
            Object us = unit.get("userStoryId");
            if (us != null && storyId != null && num(us, -1) != storyId) throw new IllegalArgumentException("item belongs to user story " + storyId + ", unit " + unitId + " is for " + us);
            if (!kind.equals(unit.get("kind"))) throw new IllegalArgumentException("unit " + unitId + " is for " + unit.get("kind") + " items");
        }
        try {
            Map<String, Object> w;
            if (top != null) {
                w = prepareGit(top, src, bugId, storyId, kind, unit);
                if (unit != null) {
                    w.put("unitId", unitId);
                    saveUnits(units);
                }
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
                if (unit != null) {
                    w.put("unitId", unitId);
                    List<Object> overlays = asList(unit.computeIfAbsent("overlays", k -> new ArrayList<>()));
                    Map<String, Object> o = map("project", project, "folder", overlay.toString(), "item", bugId);
                    if (!overlays.contains(o)) overlays.add(o);
                    saveUnits(units);
                }
            }
            // Remember it so finalize_workdir commits in exactly this place.
            Object saved = readJson(WORKDIRS_FILE);
            Map<String, Object> all = saved instanceof Map ? asMap(saved) : new LinkedHashMap<>();
            all.put(bugId + "|" + project, w);
            writeJson(WORKDIRS_FILE, all);
            Map<String, Object> ret = new LinkedHashMap<>(w);
            ret.remove("untrackedBefore"); // internal bookkeeping, not for the model
            ret.remove("snapshot");
            ret.put("commits", AUTO_COMMIT ? "one commit per item" : "none: changes stay uncommitted for the developer's review");
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
                if (!AUTO_COMMIT) {
                    // Leave everything uncommitted for the developer. This item's files = what changed since prepare_workdir.
                    Map<String, Object> snap = w.get("snapshot") instanceof Map ? asMap(w.get("snapshot")) : Map.of();
                    Map<String, Object> now = changedHashes(wtRoot, before);
                    Set<String> keys = new TreeSet<>(now.keySet());
                    keys.addAll(snap.keySet());
                    List<String> mine = new ArrayList<>();
                    for (String f : keys)
                        if (!Objects.equals(snap.get(f), now.get(f)) && (sub.isEmpty() || f.startsWith(sub + "/"))) mine.add(f);
                    out.put("filesChanged", mine);
                    out.put("committed", false);
                    out.put("uncommittedOnBranch", changed.size());
                    if (w.get("unitId") != null) recordUnitItem(String.valueOf(w.get("unitId")), bugId, project, title, url, mine, summary);
                    rememberWorkdir(bugId, project, Map.of("title", title, "url", url, "commit", "", "userStoryTitle", storyTitle,
                            "finalizedAt", Instant.now().toString()));
                    return out;
                }
                if (changed.isEmpty()) {
                    out.put("filesChanged", List.of());
                    out.put("commit", null);
                } else {
                    List<String> add = new ArrayList<>(List.of("add", "--"));
                    add.addAll(changed);
                    git(wtRoot, add.toArray(new String[0]));
                    String story = storyId == null ? "" : "\n\nUser story #" + storyId + (storyTitle.isEmpty() ? "" : ": " + storyTitle);
                    String msg = ((task ? "feat: " : "fix: ") + itemLabel + " " + title + story + "\n\n" + rootCause + "\n\n" + summary + "\n\n" + url).trim();
                    git(wtRoot, "-c", "user.name=bug-agent", "-c", "user.email=bug-agent@localhost", "commit", "-m", msg);
                    out.put("commit", git(wtRoot, "rev-parse", "--short", "HEAD"));
                    out.put("filesChanged", new ArrayList<>(changed));
                }
                if (w.get("unitId") != null) recordUnitItem(String.valueOf(w.get("unitId")), bugId, project, title, url,
                        asList(out.get("filesChanged")).stream().map(String::valueOf).collect(Collectors.toList()), summary);
                rememberWorkdir(bugId, project, Map.of("title", title, "url", url, "commit", out.get("commit") == null ? "" : out.get("commit"),
                        "userStoryTitle", storyTitle, "finalizedAt", Instant.now().toString()));
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
            if (w.get("unitId") != null) recordUnitItem(String.valueOf(w.get("unitId")), bugId, project, title, url, changed, summary);
            rememberWorkdir(bugId, project, Map.of("title", title, "url", url, "userStoryTitle", storyTitle, "finalizedAt", Instant.now().toString()));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Adds fields to the saved prepare_workdir entry for (item, project). */
    static void rememberWorkdir(long itemId, String project, Map<String, Object> extra) {
        Object saved = readJson(WORKDIRS_FILE);
        Map<String, Object> all = saved instanceof Map ? asMap(saved) : new LinkedHashMap<>();
        Object e = all.get(itemId + "|" + project);
        Map<String, Object> entry = e instanceof Map ? asMap(e) : new LinkedHashMap<>();
        entry.putAll(extra);
        all.put(itemId + "|" + project, entry);
        writeJson(WORKDIRS_FILE, all);
    }

    /** Removes one of bug-agent's own worktrees (never a developer folder); the branch and its commits stay. Returns a warning or null. */
    static String releaseWorktree(Path wtRoot) {
        if (!wtRoot.startsWith(DATA_DIR)) return "not a bug-agent worktree: " + wtRoot;
        try {
            if (!git(wtRoot, "status", "--porcelain").isEmpty())
                return "worktree " + wtRoot + " still has uncommitted changes, so it was kept; its branch can't be checked out elsewhere until it is cleaned up";
            Path main = Paths.get(git(wtRoot, "rev-parse", "--path-format=absolute", "--git-common-dir")).getParent();
            git(main, "worktree", "remove", wtRoot.toString());
            git(main, "worktree", "prune");
            return null;
        } catch (RuntimeException ex) {
            return "could not remove worktree " + wtRoot + ": " + ex.getMessage();
        }
    }

    // ---- review units: one user story + type per cycle, left uncommitted, gated on the developer's review ----

    static Map<String, Object> loadUnits() {
        Object u = readJson(UNITS_FILE);
        return u instanceof Map ? asMap(u) : new LinkedHashMap<>();
    }

    static void saveUnits(Map<String, Object> units) {
        writeJson(UNITS_FILE, units);
    }

    static void recordUnitItem(String unitId, long itemId, String project, String title, String url, List<String> files, String summary) {
        Map<String, Object> units = loadUnits();
        Object u = units.get(unitId);
        if (!(u instanceof Map)) return;
        List<Object> items = asList(asMap(u).computeIfAbsent("done", k -> new ArrayList<>()));
        items.add(map("id", itemId, "project", project, "title", title, "url", url, "files", files, "summary", summary,
                "at", Instant.now().toString()));
        saveUnits(units);
    }

    /** Where one repository of a unit stands now: waiting for review, staged, committed or discarded. */
    static Map<String, Object> repoStatus(Map<String, Object> repo) {
        Path root = Paths.get(String.valueOf(repo.get("folder")));
        String branch = String.valueOf(repo.get("branch"));
        Map<String, Object> s = map("folder", root.toString(), "branch", branch);
        if (!Files.isDirectory(root) || gitTop(root) == null) { s.put("state", "missing"); return s; }
        Set<String> baseline = new HashSet<>();
        for (Object o : asList(repo.get("baselineUntracked"))) baseline.add(String.valueOf(o));
        int staged = lines(git(root, "diff", "--cached", "--name-only")).size();
        int unstaged = lines(git(root, "diff", "--name-only")).size();
        int newFiles = (int) lines(git(root, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"))
                .stream().filter(f -> !baseline.contains(f)).count();
        String tip = refExists(root, "refs/heads/" + branch) ? git(root, "rev-parse", "refs/heads/" + branch) : null;
        boolean committed = tip != null && !tip.equals(repo.get("baselineHead"));
        s.put("projectIsOn", currentBranch(root));
        s.put("stagedFiles", staged);
        s.put("unstagedFiles", unstaged);
        s.put("newFiles", newFiles);
        int pending = staged + unstaged + newFiles;
        s.put("state", pending == 0 ? (committed ? "committed" : "discarded")
                : staged > 0 && unstaged == 0 && newFiles == 0 ? "staged, not committed" : "waiting for review");
        return s;
    }

    /** Live status of a unit; a unit is reviewed once every repository is clean again (committed or discarded). */
    static Map<String, Object> unitStatus(Map<String, Object> unit) {
        List<Object> repos = new ArrayList<>();
        boolean reviewed = true;
        Map<String, String> nameByPath = new HashMap<>();
        for (Map<String, Object> p : loadProjects()) nameByPath.put(String.valueOf(p.get("path")), String.valueOf(p.get("name")));
        for (Object r : asMap(unit.get("repos")).values()) {
            Map<String, Object> st = repoStatus(asMap(r));
            st.put("projects", asList(asMap(r).get("projects")).stream()
                    .map(pp -> nameByPath.getOrDefault(String.valueOf(pp), Paths.get(String.valueOf(pp)).getFileName().toString()))
                    .collect(Collectors.toList()));
            String state = String.valueOf(st.get("state"));
            if (!state.equals("committed") && !state.equals("discarded") && !state.equals("missing")) reviewed = false;
            repos.add(st);
        }
        Map<String, Object> out = map("unitId", unit.get("id"), "userStoryId", unit.get("userStoryId"), "userStoryTitle", unit.get("userStoryTitle"),
                "kind", unit.get("kind"), "status", unit.get("status"), "reviewed", reviewed, "repos", repos);
        if (unit.get("verdict") != null) out.put("verdict", unit.get("verdict"));
        if (unit.get("reportPath") != null) out.put("reportPath", unit.get("reportPath"));
        return out;
    }

    static String unitLabel(Map<String, Object> unit) {
        Object s = unit.get("userStoryId");
        return (s == null ? "No user story" : "US-" + s) + " (" + ("task".equals(unit.get("kind")) ? "tasks" : "bugs") + ")";
    }

    /** Checks open units; marks reviewed ones done. canStartNewWork is false while any unit waits for the developer. */
    static Map<String, Object> reviewGate() {
        Map<String, Object> units = loadUnits();
        List<Object> waiting = new ArrayList<>(), justReviewed = new ArrayList<>();
        for (Object o : units.values()) {
            Map<String, Object> unit = asMap(o);
            String status = String.valueOf(unit.get("status"));
            if (!status.equals("in_progress") && !status.equals("pending_review")) continue;
            Map<String, Object> st = unitStatus(unit);
            if (Boolean.TRUE.equals(st.get("reviewed"))) {
                unit.put("status", status.equals("in_progress") ? "abandoned" : "reviewed");
                unit.put("reviewedAt", Instant.now().toString());
                unit.put("reviewResult", asList(st.get("repos")).stream().map(r -> asMap(r).get("folder") + ": " + asMap(r).get("state")).collect(Collectors.toList()));
                st.put("status", unit.get("status"));
                justReviewed.add(st);
            } else {
                waiting.add(st);
            }
        }
        saveUnits(units);
        return map("canStartNewWork", waiting.isEmpty(), "waitingForReview", waiting, "reviewedSinceLastCheck", justReviewed);
    }

    static Object startUnit(Map<String, Object> a) {
        Map<String, Object> gate = reviewGate();
        if (!Boolean.TRUE.equals(gate.get("canStartNewWork"))) {
            String which = asList(gate.get("waitingForReview")).stream().map(w -> String.valueOf(asMap(w).get("unitId"))).collect(Collectors.joining(", "));
            throw new IllegalStateException("waiting for your review of " + which + "; commit or discard those changes first (review_gate shows where)");
        }
        String kind = kindOf(a.get("itemType") == null ? "Bug" : String.valueOf(a.get("itemType")));
        Object story = a.get("userStoryId") == null ? null : (long) num(a.get("userStoryId"), -1);
        String stamp = Instant.now().toString().replaceAll("[-:]", "").replaceAll("\\..*", "").replace("T", "-");
        String id = (story == null ? "no-story" : "US-" + story) + "-" + ("task".equals(kind) ? "feature" : "fix") + "-" + stamp;
        Map<String, Object> unit = map("id", id, "userStoryId", story, "userStoryTitle", strOr(a.get("userStoryTitle")), "kind", kind,
                "items", a.get("items") == null ? new ArrayList<>() : a.get("items"), "status", "in_progress",
                "createdAt", Instant.now().toString(), "repos", new LinkedHashMap<String, Object>());
        Map<String, Object> units = loadUnits();
        units.put(id, unit);
        saveUnits(units);
        return map("unitId", id, "kind", kind, "commits", AUTO_COMMIT ? "one per item" : "none, changes stay uncommitted for review",
                "reportDir", REPORT_DIR.toString());
    }

    /** Unified diff of everything the unit changed in one repository, including new files. */
    static String repoPatch(Map<String, Object> repo) {
        Path root = Paths.get(String.valueOf(repo.get("folder")));
        Set<String> baseline = new HashSet<>();
        for (Object o : asList(repo.get("baselineUntracked"))) baseline.add(String.valueOf(o));
        StringBuilder sb = new StringBuilder();
        String tracked = git(root, "-c", "core.quotepath=false", "diff", String.valueOf(repo.get("baselineHead")));
        if (!tracked.isEmpty()) sb.append(tracked).append('\n');
        for (String f : lines(git(root, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"))) {
            if (baseline.contains(f)) continue;
            try {
                byte[] bytes = Files.readAllBytes(root.resolve(f));
                sb.append("diff --git a/").append(f).append(" b/").append(f).append("\nnew file mode 100644\n");
                boolean binary = false;
                for (byte b : bytes) if (b == 0) { binary = true; break; }
                if (binary) { sb.append("Binary file ").append(f).append(" added\n"); continue; }
                String[] ls = new String(bytes, StandardCharsets.UTF_8).split("\r?\n", -1);
                int n = ls.length > 0 && ls[ls.length - 1].isEmpty() ? ls.length - 1 : ls.length;
                sb.append("--- /dev/null\n+++ b/").append(f).append("\n@@ -0,0 +1,").append(n).append(" @@\n");
                for (int i = 0; i < n; i++) sb.append('+').append(ls[i]).append('\n');
            } catch (IOException e) {
                sb.append("# could not read new file ").append(f).append('\n');
            }
        }
        return sb.toString();
    }

    static Map<String, Object> writeUnitPatch(Map<String, Object> unit) {
        try {
            Files.createDirectories(REPORT_DIR);
            StringBuilder all = new StringBuilder();
            List<Object> perRepo = new ArrayList<>();
            for (Object r : asMap(unit.get("repos")).values()) {
                Map<String, Object> repo = asMap(r);
                String patch = repoPatch(repo);
                all.append("# ").append(repo.get("folder")).append(" (branch ").append(repo.get("branch")).append(")\n").append(patch).append('\n');
                long files = patch.lines().filter(l -> l.startsWith("diff --git ")).count();
                perRepo.add(map("folder", repo.get("folder"), "branch", repo.get("branch"), "files", files));
            }
            Path patchPath = REPORT_DIR.resolve(unit.get("id") + ".patch");
            Files.writeString(patchPath, all.toString(), StandardCharsets.UTF_8);
            return map("patchPath", patchPath.toString(), "repos", perRepo, "bytes", (long) all.length());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Object finishUnit(Map<String, Object> a) {
        String unitId = String.valueOf(a.get("unitId"));
        Map<String, Object> units = loadUnits();
        Object u = units.get(unitId);
        if (!(u instanceof Map)) throw new IllegalArgumentException("unknown unitId " + unitId);
        Map<String, Object> unit = asMap(u);
        boolean ready = Boolean.TRUE.equals(a.get("readyForStaging"));
        unit.put("verdict", map("readyForStaging", ready, "summary", strOr(a.get("verdictSummary")),
                "issues", a.get("issues") == null ? List.of() : a.get("issues")));
        Map<String, Object> patch = writeUnitPatch(unit);
        Map<String, Object> st = unitStatus(unit);
        boolean nothing = Boolean.TRUE.equals(st.get("reviewed"));
        unit.put("status", nothing ? "nothing_to_review" : "pending_review");
        unit.put("finishedAt", Instant.now().toString());
        unit.put("patchPath", patch.get("patchPath"));

        StringBuilder md = new StringBuilder(strOr(a.get("reportMarkdown")).trim()).append("\n\n---\n\n## Where the changes are\n\n");
        for (Object r : asList(st.get("repos"))) {
            Map<String, Object> rs = asMap(r);
            md.append("- `").append(rs.get("folder")).append("` on branch `").append(rs.get("branch")).append("`: ")
                    .append(rs.get("unstagedFiles")).append(" changed, ").append(rs.get("newFiles")).append(" new file(s), not committed\n");
        }
        for (Object o : asList(unit.get("overlays"))) {
            Map<String, Object> ov = asMap(o);
            md.append("- ").append(ov.get("project")).append(" (not a git repo): reviewed copies in `").append(ov.get("folder")).append("`\n");
        }
        md.append("\nFull diff: `").append(patch.get("patchPath")).append("`\n\n")
                .append("Agent review: **").append(ready ? "ready for staging" : "not ready for staging").append("**")
                .append(strOr(a.get("verdictSummary")).isEmpty() ? "" : " - " + a.get("verdictSummary")).append("\n\n")
                .append("Review the changes in your IDE (the project folder is on the branch above). To accept: stage and commit them ")
                .append("(`git add` + `git commit`). To reject: `git restore .` and delete the new files. The next cycle starts once every ")
                .append("folder above is committed or discarded.\n");
        try {
            Files.createDirectories(REPORT_DIR);
            Path reportPath = REPORT_DIR.resolve(unitId + ".md");
            Files.writeString(reportPath, md.toString(), StandardCharsets.UTF_8);
            unit.put("reportPath", reportPath.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String alertWarning = null;
        String alertAt = null;
        if (!nothing) {
            alertWarning = scheduleAlert(unitId, REVIEW_ALERT_MINUTES);
            alertAt = Instant.now().plusSeconds(Math.round(REVIEW_ALERT_MINUTES * 60)).toString();
            unit.put("alertAt", alertAt);
        }
        saveUnits(units);
        Map<String, Object> out = map("unitId", unitId, "status", unit.get("status"), "reportPath", unit.get("reportPath"),
                "patchPath", patch.get("patchPath"), "alertAt", alertAt, "repos", st.get("repos"));
        if (alertWarning != null) out.put("alertWarning", alertWarning);
        return out;
    }

    /** Starts a detached process of this same server that waits, then shows the review status. Returns a warning or null. */
    static String scheduleAlert(String unitId, double minutes) {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String javaCmd = info.command().orElse("java");
            String srcFile = opt("BUG_AGENT_SERVER_FILE", "");
            if (srcFile.isEmpty()) {
                java.util.regex.Matcher m = Pattern.compile("\"([^\"]+\\.java)\"|(\\S+\\.java)").matcher(info.commandLine().orElse(""));
                if (m.find()) srcFile = m.group(1) != null ? m.group(1) : m.group(2);
            }
            if (srcFile.isEmpty() || !Files.exists(Paths.get(srcFile))) return "could not find AdoMcp.java to schedule the review alert";
            ProcessBuilder pb = new ProcessBuilder(javaCmd, srcFile, "--alert", unitId);
            pb.environment().put("BUG_AGENT_ALERT_DELAY_SECONDS", String.valueOf(Math.round(minutes * 60)));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(DATA_DIR.resolve("alert-process.log").toFile()));
            pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            pb.start();
            return null;
        } catch (IOException | RuntimeException e) {
            return "could not schedule the review alert: " + e.getMessage();
        }
    }

    /** Runs in the detached alert process. */
    static void runAlert(String unitId) {
        long delay = (long) parseDouble(opt("BUG_AGENT_ALERT_DELAY_SECONDS", String.valueOf(Math.round(REVIEW_ALERT_MINUTES * 60))), 900);
        try {
            Thread.sleep(Math.max(0, delay) * 1000);
        } catch (InterruptedException ignored) {
            return;
        }
        Map<String, Object> units = loadUnits();
        Object u = units.get(unitId);
        if (!(u instanceof Map)) return;
        Map<String, Object> unit = asMap(u);
        Map<String, Object> st = unitStatus(unit);
        Map<String, Object> verdict = unit.get("verdict") instanceof Map ? asMap(unit.get("verdict")) : Map.of();
        boolean ready = Boolean.TRUE.equals(verdict.get("readyForStaging"));
        int issues = asList(verdict.get("issues")).size();
        String label = unitLabel(unit);
        String title, message;
        if (Boolean.TRUE.equals(st.get("reviewed"))) {
            title = "bug-agent: " + label + " reviewed";
            String done = asList(st.get("repos")).stream().map(r -> asMap(r).get("branch") + " " + asMap(r).get("state")).collect(Collectors.joining(", "));
            message = (done.isEmpty() ? "" : done + ". ") + "The next cycle continues with the next user story.";
        } else {
            title = "bug-agent: " + label + (ready ? " ready for staging" : " NOT ready for staging");
            String where = asList(st.get("repos")).stream().map(r -> {
                Map<String, Object> rs = asMap(r);
                long n = num(rs.get("unstagedFiles"), 0) == 0 && num(rs.get("newFiles"), 0) == 0 ? (long) num(rs.get("stagedFiles"), 0)
                        : (long) (num(rs.get("unstagedFiles"), 0) + num(rs.get("newFiles"), 0) + num(rs.get("stagedFiles"), 0));
                String who = asList(rs.get("projects")).isEmpty() ? String.valueOf(Paths.get(String.valueOf(rs.get("folder"))).getFileName())
                        : asList(rs.get("projects")).stream().map(String::valueOf).collect(Collectors.joining("+"));
                return who + " on " + rs.get("branch") + ": " + rs.get("state") + " (" + n + " files)";
            }).collect(Collectors.joining("; "));
            message = (ready ? "Agent review passed" : "Agent review found " + issues + " issue(s)") + ". " + where
                    + ". Commit or discard to continue with the next cycle. Report: " + unit.get("reportPath");
        }
        notifyUser(title, message);
        unit.put("lastAlertAt", Instant.now().toString());
        saveUnits(units);
        Object report = unit.get("reportPath");
        if (report != null) {
            try {
                Files.writeString(Paths.get(String.valueOf(report)), "\n### Review status at " + Instant.now() + "\n\n" + title + ". " + message + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }
    }

    /** Desktop notification (Windows toast, macOS Notification Center, Linux notify-send) plus a line in alerts.log. */
    static void notifyUser(String title, String message) {
        try {
            Files.writeString(ALERTS_LOG, Instant.now() + "  " + title + " | " + message + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
        if (!"os".equals(NOTIFY)) return;
        String os = System.getProperty("os.name").toLowerCase();
        List<String> cmd;
        if (os.startsWith("win")) {
            String script = "$ErrorActionPreference='Stop';"
                    + "[void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime];"
                    + "$x=[Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
                    + "$t=$x.GetElementsByTagName('text');[void]$t.Item(0).AppendChild($x.CreateTextNode($env:BA_TITLE));"
                    + "[void]$t.Item(1).AppendChild($x.CreateTextNode($env:BA_MSG));"
                    + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("
                    + "'{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe').Show([Windows.UI.Notifications.ToastNotification]::new($x))";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            cmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        } else if (os.contains("mac")) {
            cmd = List.of("osascript", "-e", "display notification (system attribute \"BA_MSG\") with title (system attribute \"BA_TITLE\")");
        } else {
            cmd = List.of("notify-send", title, message);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullFile()));
            pb.environment().put("BA_TITLE", title);
            pb.environment().put("BA_MSG", message);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(DATA_DIR.resolve("alert-process.log").toFile()));
            pb.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {}
    }

    // ---- review ---------------------------------------------------------------------

    static String currentBranch(Path repo) {
        try {
            return git(repo, "rev-parse", "--abbrev-ref", "HEAD");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Commits and files bug-agent added on `branch` since `base` (first-parent, so a sync merge from master doesn't flood the list). */
    static Map<String, Object> branchSummary(Path top, String branch, String sub) {
        String base = baseRef(top);
        Map<String, Object> s = map("base", base);
        if ("HEAD".equals(base)) return s;
        String range = base + ".." + branch;
        s.put("commits", lines(git(top, "log", "--first-parent", "--no-merges", "--format=%h %s", "-n", "50", range)));
        List<String> files = new ArrayList<>(new LinkedHashSet<>(lines(git(top, "-c", "core.quotepath=false", "log", "--first-parent", "--no-merges",
                "--name-only", "--format=", range, "--", sub.isEmpty() ? "." : sub))));
        s.put("files", files);
        return s;
    }

    /** Every branch bug-agent created or used, grouped by branch, with the items on it and the projects it touches. */
    static Object reviewBranches(Map<String, Object> a) {
        Long onlyStory = a.get("userStoryId") == null ? null : (long) num(a.get("userStoryId"), -1);
        Object saved = readJson(WORKDIRS_FILE);
        Map<String, Object> all = saved instanceof Map ? asMap(saved) : Map.of();
        Map<String, Object> bugs = asMap(loadState().get("bugs"));
        Map<String, Map<String, Object>> byBranch = new TreeMap<>();
        List<Object> overlays = new ArrayList<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            Map<String, Object> w = asMap(e.getValue());
            String[] key = e.getKey().split("\\|", 2);
            long itemId = Long.parseLong(key[0]);
            String project = key.length > 1 ? key[1] : "";
            Object story = w.get("userStoryId");
            if (onlyStory != null && (story == null || num(story, -1) != onlyStory)) continue;
            Object rec = bugs.get(String.valueOf(itemId));
            Map<String, Object> item = map("id", itemId, "type", "task".equals(w.get("kind")) ? "Task" : "Bug",
                    "title", w.getOrDefault("title", rec instanceof Map ? asMap(rec).get("title") : ""),
                    "url", w.getOrDefault("url", rec instanceof Map ? asMap(rec).get("url") : ""),
                    "outcome", rec instanceof Map ? asMap(rec).get("lastOutcome") : null,
                    "commit", w.get("commit"));
            if ("overlay".equals(w.get("mode"))) {
                item.put("project", project);
                item.put("patchFolder", w.get("workdir"));
                overlays.add(item);
                continue;
            }
            String branch = String.valueOf(w.get("branch"));
            Map<String, Object> b = byBranch.computeIfAbsent(branch, k -> map("branch", k, "type", "task".equals(w.get("kind")) ? "feature" : "fix",
                    "userStoryId", story, "userStoryTitle", w.getOrDefault("userStoryTitle", ""), "items", new ArrayList<>(), "projects", new ArrayList<>()));
            @SuppressWarnings("unchecked") List<Object> items = (List<Object>) b.get("items");
            if (items.stream().noneMatch(o -> num(asMap(o).get("id"), -1) == itemId)) items.add(item);
            @SuppressWarnings("unchecked") List<Object> projects = (List<Object>) b.get("projects");
            if (projects.contains(project)) continue;
            projects.add(project);
        }
        // live git state per branch and project
        List<Object> branches = new ArrayList<>();
        for (Map<String, Object> b : byBranch.values()) {
            String branch = String.valueOf(b.get("branch"));
            List<Object> projectState = new ArrayList<>();
            for (Object pn : asList(b.get("projects"))) {
                Map<String, Object> ps = map("project", pn);
                try {
                    Map<String, Object> p = projectByName(String.valueOf(pn));
                    Path src = Paths.get(String.valueOf(p.get("path")));
                    Path top = gitTop(src);
                    if (top == null) { ps.put("state", "not a git repository any more"); projectState.add(ps); continue; }
                    boolean exists = refExists(top, "refs/heads/" + branch);
                    ps.put("branchExists", exists);
                    ps.put("projectIsOn", currentBranch(top));
                    if (exists) {
                        Path holder = checkedOutAt(top, branch);
                        ps.put("checkedOut", holder == null ? "no" : holder.equals(top) ? "in your project folder"
                                : holder.startsWith(DATA_DIR) ? "in a bug-agent worktree (checkout_branch frees it)" : "in another worktree: " + holder);
                        ps.putAll(branchSummary(top, branch, top.relativize(src).toString().replace('\\', '/')));
                    }
                } catch (RuntimeException ex) {
                    ps.put("state", ex.getMessage());
                }
                projectState.add(ps);
            }
            b.put("projects", projectState);
            branches.add(b);
        }
        return map("branches", branches, "overlayPatches", overlays);
    }

    /**
     * Checks `branch` out in the developer's own project folders so they can review it: every project the
     * branch was used in (or the ones named). Frees the branch from a bug-agent worktree first. A project
     * with uncommitted changes is left alone. Projects sharing one repository are switched once.
     */
    static Object checkoutBranch(Map<String, Object> a) {
        String branch = String.valueOf(a.get("branch")).trim();
        if (branch.isEmpty() || branch.startsWith("-")) throw new IllegalArgumentException("invalid branch name");
        List<String> names = new ArrayList<>();
        if (a.get("projects") != null) for (Object o : asList(a.get("projects"))) names.add(String.valueOf(o));
        if (names.isEmpty()) {
            Object saved = readJson(WORKDIRS_FILE);
            if (saved instanceof Map) for (Map.Entry<String, Object> e : asMap(saved).entrySet())
                if (branch.equals(asMap(e.getValue()).get("branch"))) {
                    String pn = e.getKey().split("\\|", 2)[1];
                    if (!names.contains(pn)) names.add(pn);
                }
        }
        boolean fromRecords = !names.isEmpty();
        if (!fromRecords) for (Map<String, Object> p : loadProjects()) names.add(String.valueOf(p.get("name")));

        List<Object> results = new ArrayList<>();
        Set<Path> doneRepos = new HashSet<>();
        for (String name : names) {
            Map<String, Object> r = map("project", name);
            results.add(r);
            try {
                Map<String, Object> p = projectByName(name);
                Path src = Paths.get(String.valueOf(p.get("path")));
                Path top = gitTop(src);
                if (top == null) { r.put("result", "skipped: not a git repository"); continue; }
                String sub = top.relativize(src).toString().replace('\\', '/');
                if (!doneRepos.add(top)) { r.put("result", "same repository as a project above"); continue; }
                tryFetch(top);
                boolean local = refExists(top, "refs/heads/" + branch);
                boolean remote = refExists(top, "refs/remotes/origin/" + branch);
                if (!local && !remote) {
                    r.put("result", fromRecords ? "failed: branch " + branch + " no longer exists in " + top : "skipped: no branch " + branch + " here");
                    if (!fromRecords) results.remove(r);
                    continue;
                }
                Path holder = local ? checkedOutAt(top, branch) : null;
                if (holder != null && holder.equals(top)) {
                    r.put("result", "already on " + branch);
                } else {
                    if (holder != null && holder.startsWith(DATA_DIR)) {
                        String warn = releaseWorktree(holder);
                        if (warn != null) { r.put("result", "failed: " + warn); continue; }
                    } else if (holder != null) {
                        r.put("result", "failed: " + branch + " is checked out in another worktree at " + holder);
                        continue;
                    }
                    if (hasTrackedChanges(top)) {
                        r.put("result", "refused: " + top + " has uncommitted changes on " + currentBranch(top) + "; commit or stash them first");
                        continue;
                    }
                    String was = currentBranch(top);
                    if (local) git(top, "checkout", branch);
                    else git(top, "checkout", "--track", "-b", branch, "origin/" + branch);
                    r.put("result", "checked out " + branch + " (was " + was + ")");
                    r.put("previousBranch", was);
                }
                r.put("folder", top.toString());
                r.putAll(branchSummary(top, branch, sub));
            } catch (RuntimeException ex) {
                r.put("result", "failed: " + ex.getMessage());
            }
        }
        return map("branch", branch, "projects", results);
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
