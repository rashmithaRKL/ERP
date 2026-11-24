package lk.dexlanka.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V9: a cycle count can be limited to an item category, including every sub-category below it (any depth), alone or
 * together with the item-type filter; the category is stored on the count and shown in the list and detail.
 */
class CountByCategoryTest extends InventoryTestSupport {
    String packaging, cartons, caps, polymers, cap, film, cartonLot, capLot, filmLot;

    @BeforeEach
    void categories() {
        // Packaging > Cartons > Caps (three levels) and a separate Polymers category.
        packaging = id();
        cartons = id();
        caps = id();
        polymers = id();
        update(
                "INSERT INTO item_categories(id,tenant_id,name,parent_id) VALUES(?,?,'Packaging',NULL),(?,?,'Cartons',?),(?,?,'Caps',?),(?,?,'Polymers',NULL)",
                packaging,
                t,
                cartons,
                t,
                packaging,
                caps,
                t,
                cartons,
                polymers,
                t);
        cap = id();
        film = id();
        update(
                "INSERT INTO items(id,tenant_id,sku,name,type,uom,standard_cost,reorder_level) VALUES(?,?,'CAP','Cap','PACKAGING','pcs',1,0),(?,?,'FILM','Film','PACKAGING','kg',4,0)",
                cap,
                t,
                film,
                t);
        update("UPDATE items SET category_id=? WHERE tenant_id=? AND id=?", cartons, t, carton);
        update("UPDATE items SET category_id=? WHERE tenant_id=? AND id=?", caps, t, cap);
        update("UPDATE items SET category_id=? WHERE tenant_id=? AND id=?", packaging, t, film);
        update("UPDATE items SET category_id=? WHERE tenant_id=? AND id=?", polymers, t, resin);
        cartonLot = lot(carton, raw, "RELEASED", "OPENING", "50", "2");
        capLot = lot(cap, raw, "RELEASED", "OPENING", "500", "1");
        filmLot = lot(film, raw, "RELEASED", "OPENING", "12.5", "4");
    }

    List<Object> countedLots(String count) {
        return rows("SELECT lot_id FROM stock_count_lines WHERE tenant_id=? AND count_id=? ORDER BY line_no", t, count)
                .stream()
                .map(r -> r.get("lot_id"))
                .toList();
    }

    String start(InventoryCommands.Count c) throws Exception {
        return str(json(command(warehouse, "/api/inventory/counts", c).andExpect(status().isOk())), "id");
    }

    @Test
    void aCategoryCountIncludesEverySubCategory() throws Exception {
        String count = start(new InventoryCommands.Count(raw, null, null, true, "Packaging check", packaging));
        assertThat(countedLots(count)).containsExactlyInAnyOrder(cartonLot, capLot, filmLot);
        assertThat(row("SELECT category_id FROM stock_counts WHERE tenant_id=? AND id=?", t, count)
                        .get("category_id"))
                .isEqualTo(packaging);
        assertThat(row(
                                "SELECT detail FROM audit_events WHERE tenant_id=? AND action='COUNT_STARTED' AND reference=?",
                                t,
                                number("stock_counts", count))
                        .get("detail"))
                .isEqualTo("Raw · 3 lots · category Packaging incl. sub-categories · blind count");
        query(warehouse, "/api/inventory/counts/" + count)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count.category").value("Packaging"));
        query(warehouse, "/api/inventory/counts?status=OPEN")
                .andExpect(jsonPath("$.items[0].category_id").value(packaging))
                .andExpect(jsonPath("$.items[0].category").value("Packaging"));
    }

    @Test
    void aSubCategoryCountsOnlyItsBranchAndCombinesWithTheTypeFilter() throws Exception {
        String count = start(new InventoryCommands.Count(raw, null, null, false, null, cartons));
        assertThat(countedLots(count)).containsExactlyInAnyOrder(cartonLot, capLot);
        // Those lots are now in an open count; the rest of the warehouse can still be counted by category.
        String polymerCount = start(new InventoryCommands.Count(raw, null, "RAW_MATERIAL", false, null, polymers));
        assertThat(countedLots(polymerCount)).containsExactly(resinLot);
        command(
                        warehouse,
                        "/api/inventory/counts",
                        new InventoryCommands.Count(raw, null, "PACKAGING", false, null, packaging))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Lots already in an open count: "
                                + rows(
                                                "SELECT number FROM lots WHERE tenant_id=? AND id IN (?,?) ORDER BY number",
                                                t,
                                                cartonLot,
                                                capLot)
                                        .stream()
                                        .map(r -> r.get("number") + " (" + number("stock_counts", count) + ")")
                                        .reduce((a, b) -> a + ", " + b)
                                        .orElseThrow()));
    }

    @Test
    void unknownOrForeignCategoriesAreRejectedAndEmptySelectionsExplained() throws Exception {
        command(warehouse, "/api/inventory/counts", new InventoryCommands.Count(raw, null, null, false, null, id()))
                .andExpect(status().isNotFound());
        var other = createTenant("Other categories");
        String foreign = id();
        update("INSERT INTO item_categories(id,tenant_id,name) VALUES(?,?,'Foreign')", foreign, other.id());
        command(warehouse, "/api/inventory/counts", new InventoryCommands.Count(raw, null, null, false, null, foreign))
                .andExpect(status().isNotFound());
        String empty = id();
        update("INSERT INTO item_categories(id,tenant_id,name) VALUES(?,?,'Empty')", empty, t);
        command(warehouse, "/api/inventory/counts", new InventoryCommands.Count(raw, null, null, false, null, empty))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("There is no countable stock in Raw for this selection"));
        assertThat(count("SELECT COUNT(*) FROM stock_counts WHERE tenant_id=?", t))
                .isZero();
    }
}
