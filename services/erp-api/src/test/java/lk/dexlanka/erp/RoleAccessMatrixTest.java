package lk.dexlanka.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lk.dexlanka.erp.platform.Actor;
import org.junit.jupiter.api.Test;

/**
 * V16 role access matrix (V24 adds the FINANCE role and the Accounting page). For each of the nine roles, every page listed in that role's navigation (mirroring {@code
 * roles} in {@code apps/erp-web/src/app/routes.tsx}; keep both in sync) is opened the way the web app opens it: the read
 * endpoints the page calls on load (and its tabs) must answer for that role, never 403. Endpoints a page only calls for
 * some roles (valuation, skill matrix, schedule run list, administration) are listed with those roles, as the page
 * gates them. The table is documented in {@code docs/FEATURES.md} (Role access matrix).
 */
class RoleAccessMatrixTest extends RoutingTestBase {
    static final List<String> ROLES = List.of(
            "ADMIN", "PLANNER", "WAREHOUSE", "OPERATOR", "QUALITY", "SALES", "VIEWER", "MAINTENANCE", "FINANCE");

    /** A read the page makes; {@code roles} = only for these roles (null = every role that sees the page). */
    record Read(String path, Set<String> roles) {
        static Read all(String path) {
            return new Read(path, null);
        }

        static Read only(String path, String... roles) {
            return new Read(path, Set.of(roles));
        }
    }

    /** A navigation page: who sees it (null = everyone) and what it reads. */
    record Page(String path, Set<String> roles, Function<Fixture, List<Read>> reads) {}

    record Fixture(String machine, String run, String bom, String from, String to) {}

    static final List<Page> PAGES = List.of(
            new Page("/", null, f -> List.of(Read.all("/api/state?days=14"), Read.all("/api/approval-policies"))),
            new Page(
                    "/sales",
                    Set.of("ADMIN", "PLANNER", "SALES", "WAREHOUSE"),
                    f -> List.of(Read.all("/api/sales/orders?status=open&limit=25"), Read.all("/api/partners"))),
            new Page(
                    "/inventory",
                    null,
                    f -> List.of(
                            Read.all("/api/inventory/lots"),
                            Read.all("/api/inventory/ledger"),
                            Read.all("/api/inventory/adjustments?status=APPROVAL_REQUIRED&limit=50"),
                            Read.all("/api/inventory/transfers"),
                            Read.all("/api/inventory/counts?status=OPEN&limit=50"),
                            Read.all("/api/inventory/warehouses"),
                            Read.all("/api/inventory/settings"),
                            Read.only("/api/inventory/valuation", "ADMIN", "PLANNER", "WAREHOUSE"))),
            new Page(
                    "/purchasing",
                    Set.of("ADMIN", "PLANNER", "WAREHOUSE"),
                    f -> List.of(
                            Read.all("/api/purchases/orders?status=open&limit=25"),
                            Read.all("/api/purchases/receipts"),
                            Read.all("/api/purchases/invoices"),
                            Read.all("/api/purchases/returns"))),
            new Page("/manufacturing", null, f -> List.of(Read.all("/api/manufacturing/orders?status=active"))),
            new Page("/planning", Set.of("ADMIN", "PLANNER", "WAREHOUSE"), f -> List.of(Read.all("/api/planning/mrp"))),
            new Page(
                    "/boms",
                    Set.of("ADMIN", "PLANNER"),
                    f -> List.of(
                            Read.all("/api/boms/" + f.bom() + "/explosion?quantity=1"),
                            Read.all("/api/boms/" + f.bom() + "/cost"))),
            new Page(
                    "/routings",
                    Set.of("ADMIN", "PLANNER"),
                    f -> List.of(
                            Read.all("/api/routings"),
                            Read.all("/api/calendars"),
                            Read.all("/api/calendars/available-minutes?from=" + f.from() + "&to=" + f.to()),
                            Read.all("/api/skills"),
                            Read.all("/api/skills/users"))),
            new Page(
                    "/schedule",
                    Set.of("ADMIN", "PLANNER", "OPERATOR", "MAINTENANCE", "VIEWER"),
                    f -> List.of(
                            Read.all("/api/schedule"),
                            Read.only("/api/schedule/runs?limit=20", "ADMIN", "PLANNER"),
                            Read.all("/api/schedule/runs/" + f.run()),
                            Read.all("/api/schedule/runs/" + f.run() + "/gantt?from=" + f.from() + "&to=" + f.to()),
                            Read.all(
                                    "/api/schedule/runs/" + f.run() + "/capacity?from=" + f.from() + "&to=" + f.to()))),
            new Page(
                    "/shopfloor",
                    null,
                    f -> List.of(
                            Read.all("/api/operations/work-centers"),
                            Read.all("/api/operations/queue?machineId=" + f.machine()),
                            Read.all("/api/schedule/next?machineId=" + f.machine() + "&limit=6"),
                            Read.all("/api/manufacturing/orders?status=floor&routing=none"))),
            new Page(
                    "/quality",
                    null,
                    f -> List.of(
                            Read.all("/api/quality/inspections"),
                            Read.all("/api/quality/summary"),
                            Read.all("/api/quality/people"),
                            Read.all("/api/quality/ncrs"),
                            Read.all("/api/quality/rework"),
                            Read.all("/api/quality/capas"),
                            Read.all("/api/quality/plans"),
                            Read.all("/api/quality/defect-codes"))),
            new Page(
                    "/traceability",
                    null,
                    f -> List.of(Read.all("/api/recalls"), Read.all("/api/traceability/search?q=BOT"))),
            new Page(
                    "/machines",
                    null,
                    f -> List.of(
                            Read.all("/api/maintenance/machines"),
                            Read.all("/api/maintenance/machines/" + f.machine()),
                            Read.all("/api/performance/oee?from=" + f.from() + "&to=" + f.to() + "&machine="
                                    + f.machine() + "&bucket=day"))),
            new Page(
                    "/maintenance",
                    Set.of("ADMIN", "PLANNER", "MAINTENANCE", "OPERATOR", "VIEWER"),
                    f -> List.of(
                            Read.all("/api/maintenance/work-orders?status=OPEN"),
                            Read.all("/api/maintenance/plans"),
                            Read.all("/api/maintenance/downtime"),
                            Read.all("/api/maintenance/metrics"),
                            Read.all("/api/maintenance/machines"),
                            Read.all("/api/maintenance/technicians"),
                            Read.all("/api/maintenance/spare-parts"),
                            Read.all("/api/maintenance/settings"))),
            new Page(
                    "/performance",
                    Set.of("ADMIN", "PLANNER", "MAINTENANCE", "QUALITY", "VIEWER"),
                    f -> List.of(
                            Read.all("/api/performance/oee?from=" + f.from() + "&to=" + f.to() + "&bucket=day"),
                            Read.all("/api/performance/losses?from=" + f.from() + "&to=" + f.to()))),
            new Page(
                    "/products",
                    Set.of("ADMIN", "PLANNER", "WAREHOUSE"),
                    f -> List.of(
                            Read.all("/api/items"),
                            Read.all("/api/units"),
                            Read.all("/api/item-categories"),
                            Read.all("/api/item-templates"))),
            new Page(
                    "/reports",
                    Set.of("ADMIN", "PLANNER", "FINANCE"),
                    f -> List.of(Read.all("/api/reports/costs"), Read.all("/api/reports/journal"))),
            new Page(
                    "/accounting",
                    Set.of("ADMIN", "FINANCE"),
                    f -> List.of(
                            Read.all("/api/accounting/accounts"),
                            Read.all("/api/accounting/journals?status=SUBMITTED&limit=25"),
                            Read.all("/api/accounting/policy"),
                            Read.all("/api/accounting/trial-balance"),
                            Read.all("/api/approval-policies"),
                            Read.all("/api/admin/periods"))),
            new Page(
                    "/settings",
                    null,
                    f -> List.of(
                            Read.only("/api/admin/users", "ADMIN"),
                            Read.only("/api/admin/approval-policies", "ADMIN"),
                            Read.only("/api/admin/audit", "ADMIN"),
                            Read.only("/api/admin/audit/facets", "ADMIN"),
                            Read.only("/api/admin/periods", "ADMIN"))));

    /** The pages in each role's navigation, as documented (the expected matrix). */
    static final Map<String, List<String>> NAVIGATION = Map.of(
            "ADMIN",
                    List.of(
                            "/",
                            "/sales",
                            "/inventory",
                            "/purchasing",
                            "/manufacturing",
                            "/planning",
                            "/boms",
                            "/routings",
                            "/schedule",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/maintenance",
                            "/performance",
                            "/products",
                            "/reports",
                            "/accounting",
                            "/settings"),
            "PLANNER",
                    List.of(
                            "/",
                            "/sales",
                            "/inventory",
                            "/purchasing",
                            "/manufacturing",
                            "/planning",
                            "/boms",
                            "/routings",
                            "/schedule",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/maintenance",
                            "/performance",
                            "/products",
                            "/reports",
                            "/settings"),
            "WAREHOUSE",
                    List.of(
                            "/",
                            "/sales",
                            "/inventory",
                            "/purchasing",
                            "/manufacturing",
                            "/planning",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/products",
                            "/settings"),
            "OPERATOR",
                    List.of(
                            "/",
                            "/inventory",
                            "/manufacturing",
                            "/schedule",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/maintenance",
                            "/settings"),
            "QUALITY",
                    List.of(
                            "/",
                            "/inventory",
                            "/manufacturing",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/performance",
                            "/settings"),
            "SALES",
                    List.of(
                            "/",
                            "/sales",
                            "/inventory",
                            "/manufacturing",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/settings"),
            "VIEWER",
                    List.of(
                            "/",
                            "/inventory",
                            "/manufacturing",
                            "/schedule",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/maintenance",
                            "/performance",
                            "/settings"),
            "MAINTENANCE",
                    List.of(
                            "/",
                            "/inventory",
                            "/manufacturing",
                            "/schedule",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/maintenance",
                            "/performance",
                            "/settings"),
            "FINANCE",
                    List.of(
                            "/",
                            "/inventory",
                            "/manufacturing",
                            "/shopfloor",
                            "/quality",
                            "/traceability",
                            "/machines",
                            "/reports",
                            "/accounting",
                            "/settings"));

    @Test
    void everyPageInARolesNavigationLoadsWithoutAccessDenied() throws Exception {
        // Data the pages read: a routed, released order on BM-1 and a published schedule.
        approvedRouting(bottle, "R1", op(10, "Blow", bm1), op(20, "Pack", pk1));
        releasedOrder("100", bm1, "NORMAL");
        String run = str(json(command(planner, "/api/schedule/runs", Map.of()).andExpect(status().isOk())), "id");
        command(planner, "/api/schedule/runs/" + run + "/publish", null).andExpect(status().isOk());
        LocalDate today = LocalDate.now();
        var fixture = new Fixture(
                bm1, run, bom, today.minusDays(6).toString(), today.plusDays(6).toString());
        var actors = new LinkedHashMap<String, Actor>();
        for (String role : ROLES) actors.put(role, createUser(t, role));

        var failures = new ArrayList<String>();
        for (String role : ROLES) {
            var visible = PAGES.stream()
                    .filter(p -> p.roles() == null || p.roles().contains(role))
                    .map(Page::path)
                    .toList();
            assertThat(visible).as("navigation of " + role).containsExactlyElementsOf(NAVIGATION.get(role));
            for (var page : PAGES) {
                if (!visible.contains(page.path())) continue;
                for (var read : page.reads().apply(fixture)) {
                    if (read.roles() != null && !read.roles().contains(role)) continue;
                    int code = query(actors.get(role), read.path())
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (code != 200) failures.add(role + " " + page.path() + " " + read.path() + " -> " + code);
                }
            }
        }
        assertThat(failures)
                .as("reads that fail for a role whose navigation shows the page")
                .isEmpty();
    }

    @Test
    void gatedReadsStayGatedForOtherRoles() throws Exception {
        var sales = createUser(t, "SALES");
        var maintenance = createUser(t, "MAINTENANCE");
        // The pages hide these reads from roles without the right; the server enforces it.
        query(sales, "/api/inventory/valuation").andExpect(status().isForbidden());
        query(maintenance, "/api/schedule/runs?limit=20").andExpect(status().isForbidden());
        query(operator, "/api/skills/users").andExpect(status().isForbidden());
        query(viewer, "/api/reports/costs").andExpect(status().isForbidden());
        query(quality, "/api/admin/users").andExpect(status().isForbidden());
        query(maintenance, "/api/planning/mrp").andExpect(status().isForbidden());
        // V24: the general ledger is for FINANCE and ADMIN only; FINANCE is no administrator.
        var finance = createUser(t, "FINANCE");
        query(planner, "/api/accounting/accounts").andExpect(status().isForbidden());
        query(viewer, "/api/accounting/journals").andExpect(status().isForbidden());
        query(sales, "/api/admin/periods").andExpect(status().isForbidden());
        query(finance, "/api/admin/users").andExpect(status().isForbidden());
    }
}
