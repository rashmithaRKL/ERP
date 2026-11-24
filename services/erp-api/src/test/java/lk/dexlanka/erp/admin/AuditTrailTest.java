package lk.dexlanka.erp.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The administrators' audit trail: keyset paging without gaps or repeats, actor/action/text/date filters (calendar days
 * in Asia/Colombo), facets, the streamed and audited CSV export, permissions, tenant isolation and the removed
 * {@code /api/state} key.
 */
class AuditTrailTest extends AdminTestSupport {
    @BeforeEach
    void events() {
        // 2026-03-01T19:00Z is 2 March 00:30 in Colombo (UTC+05:30).
        event("e1", "Nimal Perera", "PO_CREATED", "PO-2026-AAA111", "Resin order", "2026-03-01T10:00:00Z");
        event("e2", "Nimal Perera", "PO_APPROVED", "PO-2026-AAA111", "Approved", "2026-03-01T19:00:00Z");
        event("e3", "Dilini Fernando", "MO_CREATED", "MO-2026-BBB222", "Bottles", "2026-03-02T08:00:00Z");
        event("e4", "Dilini Fernando", "MO_APPROVE", "MO-2026-BBB222", "=HYPERLINK(\"x\")", "2026-03-03T08:00:00Z");
        event(
                "e5",
                "Amal Jayasinghe",
                "GOODS_RECEIVED",
                "GRN-9",
                "Lot RM-1 for po-2026-aaa111",
                "2026-03-04T08:00:00Z");
        event("e6", "Amal Jayasinghe", "ADJUSTMENT_POSTED", "ADJ-2026-CCC333", "Damage", "2026-03-05T08:00:00Z");
        event("e7", "Nimal Perera", "USER_CREATED", "new@example.test", "Role PLANNER", "2026-03-05T08:00:00Z");
        var other = createTenant("Other");
        update(
                "INSERT INTO audit_events(id,tenant_id,actor,action,reference,detail,created_at) VALUES(?,?,'Intruder','PO_CREATED','PO-2026-AAA111','Elsewhere','2026-03-03T00:00:00Z')",
                id(),
                other.id());
    }

    private void event(String suffix, String actor, String action, String reference, String detail, String at) {
        update(
                "INSERT INTO audit_events(id,tenant_id,actor,action,reference,detail,created_at) VALUES(?,?,?,?,?,?,?::timestamptz)",
                t + "-" + suffix,
                t,
                actor,
                action,
                reference,
                detail,
                at);
    }

    @SuppressWarnings("unchecked")
    private List<String> actions(String query) throws Exception {
        var page = json(query(admin, "/api/admin/audit" + query).andExpect(status().isOk()));
        return ((List<Map<String, Object>>) page.get("items"))
                .stream().map(e -> e.get("action").toString()).toList();
    }

    @Test
    void pagesWalkTheWholeTrailNewestFirstWithoutGapsOrRepeats() throws Exception {
        var seen = new ArrayList<String>();
        String before = "";
        int pages = 0;
        do {
            var page = json(query(admin, "/api/admin/audit?limit=3&before=" + before));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) page.get("items");
            items.forEach(e -> seen.add(e.get("id").toString()));
            before = page.get("next").toString();
            pages++;
        } while (!before.isEmpty());
        assertThat(pages).isEqualTo(3);
        // e6 and e7 share a timestamp: the id breaks the tie (descending).
        assertThat(seen).containsExactly(t + "-e7", t + "-e6", t + "-e5", t + "-e4", t + "-e3", t + "-e2", t + "-e1");
    }

    @Test
    void filtersByActorActionTextAndColomboCalendarDays() throws Exception {
        assertThat(actions("?actor=Dilini Fernando")).containsExactly("MO_APPROVE", "MO_CREATED");
        assertThat(actions("?action=PO_CREATED")).containsExactly("PO_CREATED");
        // Reference text, case-insensitive; it also matches the detail.
        assertThat(actions("?q=aaa111")).containsExactly("GOODS_RECEIVED", "PO_APPROVED", "PO_CREATED");
        assertThat(actions("?q=50%")).isEmpty(); // % is literal
        assertThat(actions("?from=2026-03-02&to=2026-03-02")).containsExactly("MO_CREATED", "PO_APPROVED");
        assertThat(actions("?from=2026-03-05")).containsExactly("USER_CREATED", "ADJUSTMENT_POSTED");
        assertThat(actions("?to=2026-03-01")).containsExactly("PO_CREATED");
        assertThat(actions("?actor=Nimal Perera&from=2026-03-02&q=po-")).containsExactly("PO_APPROVED");

        query(admin, "/api/admin/audit?from=03/02/2026").andExpect(status().isBadRequest());
        query(admin, "/api/admin/audit?from=2026-03-05&to=2026-03-01").andExpect(status().isConflict());
        query(admin, "/api/admin/audit?limit=0").andExpect(status().isConflict());

        var facets = json(query(admin, "/api/admin/audit/facets").andExpect(status().isOk()));
        assertThat(facets.get("actors")).isEqualTo(List.of("Amal Jayasinghe", "Dilini Fernando", "Nimal Perera"));
        assertThat(facets.get("actions"))
                .isEqualTo(List.of(
                        "ADJUSTMENT_POSTED",
                        "GOODS_RECEIVED",
                        "MO_APPROVE",
                        "MO_CREATED",
                        "PO_APPROVED",
                        "PO_CREATED",
                        "USER_CREATED"));
    }

    @Test
    void exportStreamsTheFilteredTrailAsEscapedCsvAndIsAudited() throws Exception {
        var response = mvc.perform(
                        get("/api/admin/audit/export?actor=Dilini Fernando").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.startsWith("attachment; filename=\"audit-trail-")))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var lines = response.replace("﻿", "").split("\r\n");
        assertThat(lines)
                .containsExactly(
                        "Date and time (Asia/Colombo),Actor,Action,Reference,Detail",
                        "\"2026-03-03 13:30:00\",\"Dilini Fernando\",\"MO_APPROVE\",\"MO-2026-BBB222\",\"'=HYPERLINK(\"\"x\"\")\"",
                        "\"2026-03-02 13:30:00\",\"Dilini Fernando\",\"MO_CREATED\",\"MO-2026-BBB222\",\"Bottles\"");
        assertThat(row("SELECT * FROM audit_events WHERE tenant_id=? AND action='AUDIT_EXPORTED'", t))
                .containsEntry("actor", "ADMIN")
                .containsEntry("detail", "2 events · actor Dilini Fernando");

        mvc.perform(get("/api/admin/audit/export").with(as(planner))).andExpect(status().isForbidden());
        query(planner, "/api/admin/audit").andExpect(status().isForbidden());
        query(warehouse, "/api/admin/audit/facets").andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantsEventsStayInvisibleAndTheStateSnapshotNoLongerCarriesThem() throws Exception {
        assertThat(actions("?actor=Intruder")).isEmpty();
        assertThat(actions("?q=Elsewhere")).isEmpty();
        query(planner, "/api/state?days=7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audit").doesNotExist())
                .andExpect(jsonPath("$.sync.status").value("NOT_CONFIGURED"));
    }
}
