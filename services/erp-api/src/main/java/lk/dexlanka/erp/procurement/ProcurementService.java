package lk.dexlanka.erp.procurement;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.stereotype.Service;

/**
 * The original single-item procurement contract ({@code POST /api/purchases}, {@code /purchases/{id}/approve},
 * {@code /purchases/{id}/receive}, {@code /receipts/{id}/invoice}) on top of the V6 documents, plus shortcuts to the
 * document commands for other packages (the base demo seed). Each method delegates to exactly one transactional
 * command, which owns the idempotency record.
 *
 * <ul>
 *   <li>{@link PurchaseOrderService} - drafts, submission, independent approval, cancel/close-short, planning drafts.
 *   <li>{@link ReceivingService} - goods receipts per line into quarantine.
 *   <li>{@link SupplierInvoiceService} - supplier invoices matched to released receipts, with variances.
 *   <li>{@link SupplierReturnService} - returns of released stock to the supplier.
 * </ul>
 */
@Service
public class ProcurementService {
    private final PurchaseOrderService orders;
    private final ReceivingService receiving;
    private final SupplierInvoiceService invoices;

    ProcurementService(PurchaseOrderService orders, ReceivingService receiving, SupplierInvoiceService invoices) {
        this.orders = orders;
        this.receiving = receiving;
        this.invoices = invoices;
    }

    /** Single-item purchase, created and submitted for independent approval in one step. */
    public Map<String, Object> purchase(Actor a, ProcurementCommands.Purchase c, String key) {
        return orders.purchase(a, c, key);
    }

    public Map<String, Object> approvePurchase(Actor a, String id, String key) {
        return orders.approve(a, id, key);
    }

    /** Receives into the order's only open line; returns the receipt id, its {@code lot_id} and status. */
    public Map<String, Object> receive(Actor a, String id, ProcurementCommands.Receive c, String key) {
        return receiving.receiveSingle(a, id, c, key);
    }

    /** Matches the whole open quantity of one released receipt at the order price. */
    public Map<String, Object> invoice(Actor a, String id, ProcurementCommands.Invoice c, String key) {
        return invoices.matchReceipt(a, id, c, key);
    }

    // ------------------------------------------------------------------ document shortcuts

    public Map<String, Object> createOrder(Actor a, ProcurementCommands.Order c, String key) {
        return orders.create(a, c, key);
    }

    public Map<String, Object> submit(Actor a, String id, String key) {
        return orders.submit(a, id, key);
    }

    public Map<String, Object> receiveLines(Actor a, String id, ProcurementCommands.GoodsReceipt c, String key) {
        return receiving.receive(a, id, c, key);
    }
}
