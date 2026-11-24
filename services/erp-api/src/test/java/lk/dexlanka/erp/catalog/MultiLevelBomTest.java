package lk.dexlanka.erp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import lk.dexlanka.erp.MultiLevelTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Multi-level BOMs (V7): explosion through semi-finished levels (yield and snapshot rounding at every level, decimals),
 * approval rules across levels (cycles, sub-BOMs approved first), where-used, revision copy/edit/compare and the
 * standard-cost roll-up.
 *
 * <p>Structure: BTL (finished, 1000 out, yield 0.99) = PRE 1000 pcs + LBL 1000 pcs + CTN 20 pcs; PRE (semi-finished,
 * 1000 out, yield 0.98) = CMP 25 kg; CMP (semi-finished compound, 100 kg out, yield 0.95) = RES 98 kg + MB 2.5 kg.
 */
class MultiLevelBomTest extends MultiLevelTestBase {
    @Autowired
    BomService boms;

    String res, mb, lbl, ctn, cmp, pre, btl, cmpBom, preBom, btlBom;

    @BeforeEach
    void structure() {
        res = item("RES", "RAW_MATERIAL", "kg", "520");
        mb = item("MB", "RAW_MATERIAL", "kg", "1850");
        lbl = item("LBL", "PACKAGING", "pcs", "2.5");
        ctn = item("CTN", "PACKAGING", "pcs", "125");
        cmp = item("CMP", "SEMI_FINISHED", "kg", "600", "0", 1);
        pre = item("PRE", "SEMI_FINISHED", "pcs", "9.6", "0", 2);
        btl = item("BTL", "FINISHED_GOOD", "pcs", "17.5");
        cmpBom = approvedBom(cmp, "R1", "100", "0.95", res, "98", mb, "2.5");
        preBom = approvedBom(pre, "R1", "1000", "0.98", cmp, "25");
        btlBom = approvedBom(btl, "R1", "1000", "0.99", pre, "1000", lbl, "1000", ctn, "20");
    }

    // ------------------------------------------------------------------ explosion

    @Test
    void explosionScalesEveryLevelWithItsOwnYieldAndTheSnapshotRounding() throws Exception {
        var x = json(query(planner, "/api/boms/" + btlBom + "/explosion?quantity=5000")
                .andExpect(status().isOk()));
        assertThat(x.get("depth")).isEqualTo(3);
        var root = map(x.get("root"));
        assertThat(root.get("sku")).isEqualTo("BTL");
        var level1 = list(root.get("children"));
        assertThat(level1).extracting(n -> n.get("sku")).containsExactly("CTN", "LBL", "PRE");
        // 1000 x 5000 / (1000 x 0.99) = 5050.505 -> whole pieces, rounded up; 20 x 5000 / 990 = 101.01 -> 102.
        assertThat(num(find(level1, "sku", "PRE").get("quantity"))).isEqualByComparingTo("5051");
        assertThat(num(find(level1, "sku", "CTN").get("quantity"))).isEqualByComparingTo("102");
        assertThat(num(find(level1, "sku", "CTN").get("exactQuantity"))).isEqualByComparingTo("101.010101010101");
        var preNode = find(level1, "sku", "PRE");
        assertThat(preNode.get("bomId")).isEqualTo(preBom);
        // Level 2: 25 kg x 5051 / (1000 x 0.98) = 128.85204081... -> 6 decimals, rounded up.
        var level2 = list(preNode.get("children"));
        assertThat(num(find(level2, "sku", "CMP").get("quantity"))).isEqualByComparingTo("128.852041");
        // Level 3 scales from the rounded level-2 quantity: 98 x 128.852041 / 95 and 2.5 x 128.852041 / 95.
        var level3 = list(find(level2, "sku", "CMP").get("children"));
        assertThat(num(find(level3, "sku", "RES").get("quantity"))).isEqualByComparingTo("132.921053");
        assertThat(num(find(level3, "sku", "MB").get("quantity"))).isEqualByComparingTo("3.390844");
        assertThat(find(level3, "sku", "RES").get("level")).isEqualTo(3);
        // Bought-in totals and their standard cost.
        var materials = list(x.get("materials"));
        assertThat(materials).extracting(m -> m.get("sku")).containsExactly("CTN", "LBL", "MB", "RES");
        assertThat(num(x.get("materialCost"))).isEqualByComparingTo("100769.508960");
        assertThat(list(x.get("warnings"))).isEmpty();

        // The explosion matches what manufacturing snapshots, level by level.
        String order = order(btlBom, "5000", LocalDate.now().plusDays(10));
        assertThat(decimal(
                        "SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=? AND item_id=?",
                        t,
                        order,
                        pre))
                .isEqualByComparingTo("5051");
        String preOrder = order(preBom, "5051", LocalDate.now().plusDays(8));
        assertThat(decimal(
                        "SELECT required_qty FROM order_materials WHERE tenant_id=? AND order_id=? AND item_id=?",
                        t,
                        preOrder,
                        cmp))
                .isEqualByComparingTo("128.852041");

        // Default quantity is the base output; a product's latest approved revision can be exploded directly.
        var byProduct =
                json(query(warehouse, "/api/boms/explosion?productId=" + pre).andExpect(status().isOk()));
        assertThat(num(byProduct.get("quantity"))).isEqualByComparingTo("1000");
        assertThat(map(byProduct.get("bom")).get("bomId")).isEqualTo(preBom);
        // Pieces must be whole; a product without an approved revision has nothing to explode.
        query(planner, "/api/boms/" + btlBom + "/explosion?quantity=2.5").andExpect(status().isConflict());
        query(planner, "/api/boms/explosion?productId=" + lbl).andExpect(status().isConflict());
    }

    @Test
    void decimalStructuresStayExactAndDraftsExplodeThroughApprovedSubAssemblies() throws Exception {
        // A kg product with a fractional yield and fractional quantity.
        String paste = item("PASTE", "SEMI_FINISHED", "kg", "0");
        String bom = draftBom(paste, "D1", "3", "0.875", cmp, "1.333333", mb, "0.000007");
        var x = json(
                query(planner, "/api/boms/" + bom + "/explosion?quantity=7.5").andExpect(status().isOk()));
        var level1 = list(map(x.get("root")).get("children"));
        // 1.333333 x 7.5 / (3 x 0.875) = 3.809522857... -> 3.809523; 0.000007 x 7.5 / 2.625 = 0.00002 exactly.
        assertThat(num(find(level1, "sku", "CMP").get("quantity"))).isEqualByComparingTo("3.809523");
        assertThat(num(find(level1, "sku", "MB").get("quantity"))).isEqualByComparingTo("0.00002");
        assertThat(map(x.get("bom")).get("status")).isEqualTo("DRAFT");
        // The approved compound below the draft still expands.
        assertThat(list(find(level1, "sku", "CMP").get("children"))).hasSize(2);
    }

    // ------------------------------------------------------------------ approval rules across levels

    @Test
    void approvalRejectsCyclesAcrossLevelsAndSemiFinishedComponentsWithoutAnApprovedBom() throws Exception {
        // CMP R2 would consume BTL's preform: CMP -> PRE -> CMP.
        String cyclic = draftBom(cmp, "R2", "100", "1", pre, "1");
        command(admin, "/api/boms/" + cyclic + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                        .contains("BOM cycle")
                        .contains("CMP → PRE → CMP"));
        // Three levels deep: a new semi-finished item used by CMP that itself consumes PRE.
        String sub = item("SUB", "SEMI_FINISHED", "kg", "1");
        approvedBom(sub, "R1", "1", "1", pre, "2");
        String deep = draftBom(cmp, "R3", "100", "1", sub, "1");
        assertThatThrownBy(() -> catalog.approveBom(admin, deep, id())).hasMessageContaining("CMP → SUB → PRE → CMP");
        assertThat(row("SELECT status FROM boms WHERE tenant_id=? AND id=?", t, deep)
                        .get("status"))
                .isEqualTo("DRAFT");

        // A semi-finished component needs its own approved BOM first (bottom-up approval).
        String bare = item("BARE", "SEMI_FINISHED", "pcs", "1");
        String parent = draftBom(btl, "R9", "1", "1", bare, "1");
        assertThatThrownBy(() -> catalog.approveBom(admin, parent, id()))
                .hasMessageContaining("Approve the BOM of each semi-finished component first")
                .hasMessageContaining("BARE");
        approvedBom(bare, "R1", "1", "1", res, "1");
        catalog.approveBom(admin, parent, id());
        assertThat(row("SELECT status FROM boms WHERE tenant_id=? AND id=?", t, parent)
                        .get("status"))
                .isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ where-used

    @Test
    void whereUsedClimbsEveryLevelToTheEndProducts() throws Exception {
        String draft = draftBom(btl, "R2", "1000", "1", pre, "1000");
        var w = json(query(planner, "/api/boms/where-used?itemId=" + res).andExpect(status().isOk()));
        assertThat(w.get("depth")).isEqualTo(3);
        var level1 = list(w.get("parents"));
        assertThat(level1).singleElement().satisfies(n -> {
            assertThat(n.get("sku")).isEqualTo("CMP");
            assertThat(num(n.get("lineQty"))).isEqualByComparingTo("98");
        });
        var level2 = list(level1.getFirst().get("parents"));
        assertThat(level2).extracting(n -> n.get("sku")).containsExactly("PRE");
        var level3 = list(level2.getFirst().get("parents"));
        // Both BTL revisions consume the preform: the approved R1 and the new draft R2 (newest revision first).
        assertThat(level3).extracting(n -> n.get("revision")).containsExactly("R2", "R1");
        assertThat(level3).extracting(n -> n.get("status")).containsExactly("DRAFT", "APPROVED");
        assertThat(find(level3, "bomId", draft).get("active")).isEqualTo(false);
        assertThat(find(level3, "bomId", btlBom).get("active")).isEqualTo(true);
        assertThat(list(w.get("endProducts"))).extracting(n -> n.get("sku")).containsExactly("BTL");
        // A finished good is used nowhere.
        assertThat(list(json(query(planner, "/api/boms/where-used?itemId=" + btl))
                        .get("parents")))
                .isEmpty();
    }

    // ------------------------------------------------------------------ revision tools

    @Test
    void approvedRevisionsAreCopiedIntoEditableDraftsAndCompared() throws Exception {
        String key = id();
        var copy = json(command(planner, "/api/boms/" + btlBom + "/copy", new BomCommands.Copy("R2"), key)
                .andExpect(status().isOk()));
        String r2 = str(copy, "id");
        assertThat(copy).containsEntry("status", "DRAFT").containsEntry("sourceId", btlBom);
        var draft = row("SELECT * FROM boms WHERE tenant_id=? AND id=?", t, r2);
        assertThat(draft.get("source_bom_id")).isEqualTo(btlBom);
        assertThat(dec(draft.get("yield_factor"))).isEqualByComparingTo("0.99");
        assertThat(count("SELECT COUNT(*) FROM bom_lines WHERE tenant_id=? AND bom_id=?", t, r2))
                .isEqualTo(3);
        // Idempotent replay; key reuse with another body is rejected.
        assertThat(str(
                        json(command(planner, "/api/boms/" + btlBom + "/copy", new BomCommands.Copy("R2"), key)
                                .andExpect(status().isOk())),
                        "id"))
                .isEqualTo(r2);
        command(planner, "/api/boms/" + btlBom + "/copy", new BomCommands.Copy("R3"), key)
                .andExpect(status().isConflict());
        // Revision references are unique per product; drafts are edited, not copied.
        command(planner, "/api/boms/" + btlBom + "/copy", new BomCommands.Copy("r2"))
                .andExpect(status().isConflict());
        command(planner, "/api/boms/" + r2 + "/copy", new BomCommands.Copy("R4"))
                .andExpect(status().isConflict());
        command(warehouse, "/api/boms/" + btlBom + "/copy", new BomCommands.Copy("R5"))
                .andExpect(status().isForbidden());

        // Edit the draft: heavier carton spec, label removed, masterbatch added; same output and yield.
        var edit = new BomCommands.Draft(
                bd("1000"),
                bd("0.99"),
                List.of(
                        new CatalogCommands.BomLine(pre, bd("1000")),
                        new CatalogCommands.BomLine(ctn, bd("25")),
                        new CatalogCommands.BomLine(mb, bd("0.1"))));
        command(planner, "/api/boms/" + r2, edit).andExpect(status().isOk());
        command(planner, "/api/boms/" + btlBom, edit).andExpect(status().isConflict()); // approved: immutable
        command(
                        planner,
                        "/api/boms/" + r2,
                        new BomCommands.Draft(bd("1000"), bd("1"), List.of(new CatalogCommands.BomLine(btl, bd("1")))))
                .andExpect(status().isConflict()); // own product

        var c = json(
                query(planner, "/api/boms/compare?from=" + btlBom + "&to=" + r2).andExpect(status().isOk()));
        var lines = list(c.get("lines"));
        assertThat(find(lines, "sku", "PRE").get("change")).isEqualTo("UNCHANGED");
        var carton = find(lines, "sku", "CTN");
        assertThat(carton.get("change")).isEqualTo("CHANGED");
        assertThat(num(carton.get("fromQty"))).isEqualByComparingTo("20");
        assertThat(num(carton.get("toQty"))).isEqualByComparingTo("25");
        assertThat(num(carton.get("costDelta"))).isEqualByComparingTo("0.631313");
        assertThat(find(lines, "sku", "LBL").get("change")).isEqualTo("REMOVED");
        assertThat(num(find(lines, "sku", "LBL").get("costDelta"))).isEqualByComparingTo("-2.525253");
        assertThat(find(lines, "sku", "MB").get("change")).isEqualTo("ADDED");
        assertThat(num(find(lines, "sku", "MB").get("costDelta"))).isEqualByComparingTo("0.186869");
        var summary = map(c.get("summary"));
        assertThat(summary)
                .containsEntry("ADDED", 1)
                .containsEntry("REMOVED", 1)
                .containsEntry("CHANGED", 1);
        assertThat(num(summary.get("fromUnitCost"))).isEqualByComparingTo("20.127429");
        assertThat(num(summary.get("unitCostDelta")))
                .isCloseTo(bd("-1.707071"), org.assertj.core.data.Offset.offset(bd("0.000002")));
        // Revisions of different products are not comparable.
        query(planner, "/api/boms/compare?from=" + btlBom + "&to=" + preBom).andExpect(status().isConflict());

        // The edited draft is approved like any other revision.
        catalog.approveBom(admin, r2, id());
        assertThat(row("SELECT status FROM boms WHERE tenant_id=? AND id=?", t, r2)
                        .get("status"))
                .isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ cost roll-up

    @Test
    void standardCostRollsUpThroughEveryLevel() throws Exception {
        var cost = json(query(planner, "/api/boms/" + btlBom + "/cost").andExpect(status().isOk()));
        // CMP: (98 x 520 + 2.5 x 1850) / 95 = 585.105263 per kg; PRE: 25 x that / 980 = 14.926155 per piece.
        assertThat(num(cost.get("unitCost"))).isEqualByComparingTo("20.127429");
        // Single level uses the preform's item standard cost (9.6) instead of its roll-up.
        assertThat(num(cost.get("singleLevelCost")))
                .isEqualByComparingTo(
                        bd("9.6").add(bd("2.5")).add(bd("2.5")).divide(bd("0.99"), 6, java.math.RoundingMode.HALF_UP));
        assertThat(cost.get("depth")).isEqualTo(3);
        var levels = list(cost.get("levels"));
        assertThat(levels).extracting(l -> l.get("level")).containsExactly(1, 3);
        assertThat(num(find(levels, "level", 1).get("cost"))).isEqualByComparingTo("5.050505");
        assertThat(num(find(levels, "level", 3).get("cost"))).isEqualByComparingTo("15.076924");
        var preform = find(list(cost.get("components")), "sku", "PRE");
        assertThat(preform.get("rolledUp")).isEqualTo(true);
        assertThat(num(preform.get("unitCost"))).isEqualByComparingTo("14.926155");
        assertThat(list(cost.get("missingBoms"))).isEmpty();

        // The workspace snapshot carries the roll-up of every revision for the BOM cards.
        var state = json(query(viewer, "/api/state?days=14").andExpect(status().isOk()));
        var costs = list(state.get("bomCosts"));
        assertThat(num(find(costs, "bom_id", btlBom).get("unitCost"))).isEqualByComparingTo("20.127429");
        assertThat(find(costs, "bom_id", btlBom).get("semi_finished")).isEqualTo(1);
        assertThat(find(costs, "bom_id", cmpBom).get("depth")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ permissions and tenancy

    @Test
    void readsAreRoleGatedAndTenantScoped() throws Exception {
        query(operator, "/api/boms/" + btlBom + "/explosion").andExpect(status().isForbidden());
        query(viewer, "/api/boms/where-used?itemId=" + res).andExpect(status().isForbidden());
        query(quality, "/api/boms/" + btlBom + "/cost").andExpect(status().isForbidden());
        query(warehouse, "/api/boms/" + btlBom + "/cost").andExpect(status().isOk());

        var other = createTenant("Other organization");
        var stranger = createUser(other.id(), "ADMIN");
        query(stranger, "/api/boms/" + btlBom + "/explosion").andExpect(status().isNotFound());
        query(stranger, "/api/boms/where-used?itemId=" + res).andExpect(status().isNotFound());
        query(stranger, "/api/boms/compare?from=" + btlBom + "&to=" + btlBom).andExpect(status().isNotFound());
        query(stranger, "/api/boms/" + btlBom + "/cost").andExpect(status().isNotFound());
        command(stranger, "/api/boms/" + btlBom + "/copy", new BomCommands.Copy("X1"))
                .andExpect(status().isNotFound());
        var state = json(query(stranger, "/api/state?days=14").andExpect(status().isOk()));
        assertThat(list(state.get("bomCosts"))).noneMatch(r -> btlBom.equals(r.get("bom_id")));
    }
}
