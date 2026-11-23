package lk.dexlanka.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class WarehouseOperationsTest extends InventoryTestSupport {

    @Test
    void defaultWarehousesGetCodesAndNewWarehousesAreCreatedAndEdited() throws Exception {
        assertThat(rows("SELECT code FROM warehouses WHERE tenant_id=? ORDER BY code", t))
                .extracting(r -> r.get("code"))
                .containsExactly("FG", "RM");

        var created = json(command(
                        warehouse,
                        "/api/inventory/warehouses",
                        new InventoryCommands.Warehouse("Packaging store", "pkg", "RAW"))
                .andExpect(status().isOk()));
        String id = str(created, "id");
        var row = row("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", t, id);
        assertThat(row.get("code")).isEqualTo("PKG");
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("factory_id")).isEqualTo(tenant.plant());
        assertThat(count(
                        "SELECT COUNT(*) FROM audit_events WHERE tenant_id=? AND action='WAREHOUSE_CREATED' AND reference='PKG'",
                        t))
                .isEqualTo(1);

        command(warehouse, "/api/inventory/warehouses", new InventoryCommands.Warehouse("Another", "PKG", "OTHER"))
                .andExpect(status().isConflict());
        command(operator, "/api/inventory/warehouses", new InventoryCommands.Warehouse("Nope", "NOPE", "OTHER"))
                .andExpect(status().isForbidden());
        command(admin, "/api/inventory/warehouses", Map.of("name", "Bad kind", "code", "BAD", "kind", "SHOP"))
                .andExpect(status().isBadRequest());

        command(
                        planner,
                        "/api/inventory/warehouses/" + id,
                        new InventoryCommands.WarehouseUpdate("Packaging & labels", "PKG-1", "OTHER", true))
                .andExpect(status().isOk());
        row = row("SELECT * FROM warehouses WHERE tenant_id=? AND id=?", t, id);
        assertThat(row.get("name")).isEqualTo("Packaging & labels");
        assertThat(row.get("code")).isEqualTo("PKG-1");
        assertThat(row.get("kind")).isEqualTo("OTHER");

        var list = inventory.warehouses(warehouse);
        assertThat((Iterable<?>) list.get("items")).hasSize(3);
    }

    @Test
    void warehouseWithStockOrOpenDocumentsCannotBeDeactivated() {
        String store = newWarehouse("Packaging store", "PKG", "RAW");
        String lot = lot(carton, store, "RELEASED", "OPENING", "10", "2");
        assertThatThrownBy(() -> warehouses.update(
                        admin,
                        store,
                        new InventoryCommands.WarehouseUpdate("Packaging store", "PKG", "RAW", false),
                        id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("holds stock");
        assertThatThrownBy(() -> warehouses.update(
                        admin,
                        store,
                        new InventoryCommands.WarehouseUpdate("Packaging store", "PKG", "OTHER", true),
                        id()))
                .hasMessageContaining("Empty the warehouse");

        // Empty it with a transfer back to the raw warehouse; an open draft referencing it still blocks.
        String back = transferDraft(warehouse, store, raw, lot, "10");
        assertThatThrownBy(() -> warehouses.update(
                        admin,
                        store,
                        new InventoryCommands.WarehouseUpdate("Packaging store", "PKG", "RAW", false),
                        id()))
                .hasMessageContaining("holds stock");
        transfers.post(warehouse, back, id());
        String draft = transferDraft(warehouse, raw, store, resinLot, "1");
        assertThatThrownBy(() -> warehouses.update(
                        admin,
                        store,
                        new InventoryCommands.WarehouseUpdate("Packaging store", "PKG", "RAW", false),
                        id()))
                .hasMessageContaining("open adjustments, transfers and counts");
        transfers.cancel(warehouse, draft, id());

        warehouses.update(
                admin, store, new InventoryCommands.WarehouseUpdate("Packaging store", "PKG", "RAW", false), id());
        assertThat(row("SELECT active FROM warehouses WHERE tenant_id=? AND id=?", t, store)
                        .get("active"))
                .isEqualTo(false);

        // An inactive warehouse receives nothing: no transfer, no found stock, and the database rejects new lots.
        assertThatThrownBy(() -> transferDraft(warehouse, raw, store, resinLot, "1"))
                .hasMessageContaining("inactive");
        assertThatThrownBy(() -> draft(warehouse, "FOUND", found(carton, store, "1", "2", null)))
                .hasMessageContaining("inactive");
        assertThatThrownBy(() -> lot(carton, store, "RELEASED", "OPENING", "1", "2"))
                .hasMessageContaining("inactive");

        warehouses.update(
                admin, store, new InventoryCommands.WarehouseUpdate("Packaging store", "PKG", "RAW", true), id());
        assertThat(row("SELECT active FROM warehouses WHERE tenant_id=? AND id=?", t, store)
                        .get("active"))
                .isEqualTo(true);
    }

    @Test
    void defaultWarehousesKeepWorkingForReceiptsAndProductionOutput() {
        String production = "SELECT id FROM warehouses WHERE tenant_id=? AND kind='FINISHED' ORDER BY id LIMIT 1";
        assertThat(row(production, t).get("id")).isEqualTo(finished);
        String second = newWarehouse("Dispatch bay", "FG-2", "FINISHED");
        assertThat(second).startsWith(finished);
        assertThat(row(production, t).get("id")).isEqualTo(finished);

        assertThatThrownBy(() -> warehouses.update(
                        admin,
                        finished,
                        new InventoryCommands.WarehouseUpdate("Finished", "FG", "FINISHED", false),
                        id()))
                .hasMessageContaining("production output");
        assertThatThrownBy(() -> warehouses.update(
                        admin, finished, new InventoryCommands.WarehouseUpdate("Finished", "FG", "OTHER", true), id()))
                .hasMessageContaining("production output");
        // The second finished-goods warehouse is an ordinary warehouse.
        warehouses.update(
                admin, second, new InventoryCommands.WarehouseUpdate("Dispatch bay", "FG-2", "FINISHED", false), id());

        // The only raw-material warehouse must stay active (another tenant without stock).
        var other = createTenant();
        var otherAdmin = createUser(other.id(), "ADMIN");
        assertThatThrownBy(() -> warehouses.update(
                        otherAdmin,
                        other.rawWarehouse(),
                        new InventoryCommands.WarehouseUpdate("Raw", "RM", "RAW", false),
                        id()))
                .hasMessageContaining("at least one active raw-material warehouse");
    }

    @Test
    void warehouseCommandsAreIdempotentAndTenantScoped() throws Exception {
        String key = id();
        var body = new InventoryCommands.Warehouse("Quarantine cage", "QC", "QUARANTINE");
        var first = json(command(admin, "/api/inventory/warehouses", body, key).andExpect(status().isOk()));
        var replay = json(command(admin, "/api/inventory/warehouses", body, key).andExpect(status().isOk()));
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT COUNT(*) FROM warehouses WHERE tenant_id=? AND code='QC'", t))
                .isEqualTo(1);
        command(admin, "/api/inventory/warehouses", new InventoryCommands.Warehouse("Other", "QC2", "OTHER"), key)
                .andExpect(status().isConflict());

        var other = createTenant();
        var outsider = createUser(other.id(), "ADMIN");
        command(
                        outsider,
                        "/api/inventory/warehouses/" + first.get("id"),
                        new InventoryCommands.WarehouseUpdate("Taken", "QC", "QUARANTINE", true))
                .andExpect(status().isNotFound());
        assertThat((List<Map<String, Object>>) inventory.warehouses(outsider).get("items"))
                .extracting(r -> r.get("id"))
                .doesNotContain(first.get("id"));
    }
}
