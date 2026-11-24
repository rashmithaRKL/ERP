package lk.dexlanka.erp.procurement;

import static lk.dexlanka.erp.platform.Store.dec;
import static lk.dexlanka.erp.platform.Store.id;
import static lk.dexlanka.erp.platform.Store.money;
import static lk.dexlanka.erp.platform.Store.require;
import static lk.dexlanka.erp.platform.Store.str;
import static lk.dexlanka.erp.procurement.PurchaseRules.amount;
import static lk.dexlanka.erp.procurement.PurchaseRules.lines;
import static lk.dexlanka.erp.procurement.PurchaseRules.plain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier invoices matched line by line to quality-released receipts of one purchase order (three-way match: order
 * price, accepted receipt quantity, invoice).
 *
 * <p>Postings extend the original receipt match: goods-received clearing is released at the receipts' posted cost
 * (debit GRNI, credit ACCOUNTS_PAYABLE, as before V6), and the difference between the billed amount and that cost is a
 * purchase price variance (debit PURCHASE_PRICE_VARIANCE / credit ACCOUNTS_PAYABLE when billed above the order price,
 * the reverse when below) so accounts payable equals what the supplier billed. Inventory and lot costs are not
 * revalued: the stock may already be consumed (FIFO actual cost stays at the order price). A line may bill less than the
 * receipt's open quantity (a quantity variance; the rest stays open for a later invoice) but never more.
 */
@Service
public class SupplierInvoiceService {
    static final String PRICE_VARIANCE_ACCOUNT = "PURCHASE_PRICE_VARIANCE";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");

    private final Store s;
    private final PurchaseRules rules;

    SupplierInvoiceService(Store s, PurchaseRules rules) {
        this.s = s;
        this.rules = rules;
    }

    @Transactional
    public Map<String, Object> match(
            Actor a, String purchaseOrderId, ProcurementCommands.SupplierInvoice c, String key) {
        a.allow();
        s.validDate(c.invoiceDate());
        return s.once(a, key, "po-invoice:" + purchaseOrderId, c, () -> {
            var po = rules.locked(a, purchaseOrderId);
            String reference = c.supplierReference().strip();
            var duplicate = s.list(
                    "SELECT number FROM supplier_invoices WHERE tenant_id=? AND supplier_id=? AND lower(supplier_reference)=lower(?) LIMIT 1",
                    a.tenant(),
                    po.get("supplier_id"),
                    reference);
            if (!duplicate.isEmpty())
                require(
                        false,
                        "Supplier invoice " + reference + " from this supplier is already recorded as "
                                + duplicate.getFirst().get("number"));
            return post(a, po, reference, c.invoiceDate(), c.notes(), c.lines());
        });
    }

    /** Whole-receipt match at the order price (the original {@code POST /api/receipts/{id}/invoice}). */
    @Transactional
    public Map<String, Object> matchReceipt(Actor a, String receiptId, ProcurementCommands.Invoice c, String key) {
        a.allow();
        return s.once(a, key, "invoice:" + receiptId, c, () -> {
            var r = s.one(
                    "SELECT r.*,l.status FROM receipts r JOIN lots l ON l.tenant_id=r.tenant_id AND l.id=r.lot_id WHERE r.tenant_id=? AND r.id=?",
                    a.tenant(),
                    receiptId);
            require(r.get("status").equals("RELEASED"), "Only quality-released receipts can be matched");
            BigDecimal open = open(r);
            require(open.signum() > 0, "This receipt is already invoiced");
            var po = rules.locked(a, str(r, "purchase_order_id"));
            post(
                    a,
                    po,
                    c.reference(),
                    LocalDate.now(BUSINESS_ZONE).toString(),
                    "",
                    List.of(new ProcurementCommands.InvoiceLine(receiptId, open, dec(r.get("unit_cost")))));
            return Map.of("id", receiptId, "invoice_reference", c.reference());
        });
    }

    private Map<String, Object> post(
            Actor a,
            Map<String, Object> po,
            String reference,
            String invoiceDate,
            String notes,
            List<ProcurementCommands.InvoiceLine> lines) {
        s.openPeriod(a);
        String poId = str(po, "id");
        require(
                PurchaseRules.APPROVED_OR_LATER.contains(str(po, "status")),
                "Only approved purchase orders with receipts can be invoiced");
        // Pass 1: validate every line and compute its match (posted history cannot be updated afterwards).
        var seen = new HashSet<String>();
        var computed = new ArrayList<Map<String, Object>>();
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal matched = BigDecimal.ZERO;
        boolean variance = false;
        var details = new ArrayList<String>();
        for (var l : lines) {
            require(seen.add(l.receiptId()), "Each receipt can appear only once per supplier invoice");
            var r = s.one("""
                    SELECT r.*,lt.number AS lot,lt.status AS lot_status,pl.line_no,pl.item_id,i.uom,i.sku
                    FROM receipts r
                    JOIN lots lt ON lt.tenant_id=r.tenant_id AND lt.id=r.lot_id
                    JOIN purchase_order_lines pl ON pl.tenant_id=r.tenant_id AND pl.id=r.purchase_order_line_id
                    JOIN items i ON i.tenant_id=pl.tenant_id AND i.id=pl.item_id
                    WHERE r.tenant_id=? AND r.purchase_order_id=? AND r.id=?
                    FOR UPDATE OF r""", a.tenant(), poId, l.receiptId());
            require(
                    r.get("lot_status").equals("RELEASED"),
                    "Receipt " + r.get("number") + " (lot " + r.get("lot")
                            + ") is not quality-released; only released receipts can be matched");
            s.quantity(str(r, "uom"), l.quantity());
            BigDecimal open = open(r);
            require(
                    l.quantity().compareTo(open) <= 0,
                    open.signum() == 0
                            ? "Receipt " + r.get("number") + " (lot " + r.get("lot") + ") is already fully invoiced"
                            : "Invoice quantity " + plain(l.quantity()) + " " + r.get("uom") + " exceeds the "
                                    + plain(open) + " " + r.get("uom") + " received and not yet invoiced on receipt "
                                    + r.get("number") + " (lot " + r.get("lot")
                                    + "); ask the supplier for a corrected invoice");
            BigDecimal cost = dec(r.get("unit_cost"));
            BigDecimal amountBilled = money(l.quantity().multiply(l.unitPrice()));
            // The line that invoices the rest of a receipt releases exactly the clearing still open for it.
            BigDecimal clearing;
            if (l.quantity().compareTo(open) == 0) {
                BigDecimal released = s.db().queryForObject(
                                "SELECT COALESCE((SELECT SUM(matched_amount) FROM supplier_invoice_lines WHERE tenant_id=? AND receipt_id=?),0)+COALESCE((SELECT SUM(clearing_amount) FROM supplier_returns WHERE tenant_id=? AND receipt_id=?),0)",
                                BigDecimal.class,
                                a.tenant(),
                                l.receiptId(),
                                a.tenant(),
                                l.receiptId());
                clearing = money(dec(r.get("value_amount")).subtract(released).max(BigDecimal.ZERO));
            } else clearing = money(l.quantity().multiply(cost));
            BigDecimal priceVariance = amountBilled.subtract(clearing);
            BigDecimal quantityVariance = l.quantity().subtract(open);
            boolean flagged = l.unitPrice().compareTo(cost) != 0 || quantityVariance.signum() != 0;
            variance |= flagged;
            billed = billed.add(amountBilled);
            matched = matched.add(clearing);
            var row = new LinkedHashMap<String, Object>();
            row.put("receipt", r);
            row.put("line", l);
            row.put("amount", amountBilled);
            row.put("clearing", clearing);
            row.put("priceVariance", priceVariance);
            row.put("open", open);
            row.put("quantityVariance", quantityVariance);
            computed.add(row);
            details.add(plain(l.quantity()) + " " + r.get("uom") + " of " + r.get("sku") + " (lot " + r.get("lot") + ")"
                    + (flagged
                            ? " · variance: "
                                    + (quantityVariance.signum() != 0
                                            ? plain(quantityVariance.negate()) + " " + r.get("uom") + " not billed"
                                            : "")
                                    + (quantityVariance.signum() != 0
                                                    && l.unitPrice().compareTo(cost) != 0
                                            ? ", "
                                            : "")
                                    + (l.unitPrice().compareTo(cost) != 0
                                            ? "price " + plain(l.unitPrice()) + " vs " + plain(cost) + " per "
                                                    + r.get("uom")
                                            : "")
                            : ""));
        }
        // Pass 2: write the document, update the receipts, post the journals.
        String id = id();
        String number = rules.documentNumber(a, "supplier_invoices", "SINV");
        BigDecimal priceVariance = billed.subtract(matched);
        s.db().update(
                        "INSERT INTO supplier_invoices(id,tenant_id,number,purchase_order_id,supplier_id,supplier_reference,invoice_date,amount,matched_amount,price_variance,status,notes,created_by) VALUES(?,?,?,?,?,?,?::date,?,?,?,?,?,?)",
                        id,
                        a.tenant(),
                        number,
                        poId,
                        po.get("supplier_id"),
                        reference,
                        invoiceDate,
                        billed,
                        matched,
                        priceVariance,
                        variance ? "VARIANCE" : "MATCHED",
                        notes == null ? "" : notes.strip(),
                        a.id());
        int lineNo = 0;
        for (var row : computed) {
            @SuppressWarnings("unchecked")
            var r = (Map<String, Object>) row.get("receipt");
            var l = (ProcurementCommands.InvoiceLine) row.get("line");
            s.db().update(
                            "INSERT INTO supplier_invoice_lines(id,tenant_id,invoice_id,purchase_order_id,purchase_order_line_id,receipt_id,line_no,item_id,quantity,unit_price,amount,po_unit_cost,matched_amount,price_variance,open_quantity,quantity_variance) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            id(),
                            a.tenant(),
                            id,
                            poId,
                            r.get("purchase_order_line_id"),
                            l.receiptId(),
                            ++lineNo,
                            r.get("item_id"),
                            l.quantity(),
                            l.unitPrice(),
                            row.get("amount"),
                            r.get("unit_cost"),
                            row.get("clearing"),
                            row.get("priceVariance"),
                            row.get("open"),
                            row.get("quantityVariance"));
            s.db().update(
                            "UPDATE receipts SET invoiced=invoiced+?,invoice_reference=? WHERE tenant_id=? AND id=?",
                            l.quantity(),
                            reference,
                            a.tenant(),
                            l.receiptId());
        }
        s.journal(
                a,
                reference,
                "Three-way matched supplier invoice for " + po.get("number"),
                "GRNI",
                "ACCOUNTS_PAYABLE",
                matched);
        if (priceVariance.signum() > 0)
            s.journal(
                    a,
                    reference,
                    "Purchase price variance on supplier invoice for " + po.get("number"),
                    PRICE_VARIANCE_ACCOUNT,
                    "ACCOUNTS_PAYABLE",
                    priceVariance);
        else if (priceVariance.signum() < 0)
            s.journal(
                    a,
                    reference,
                    "Purchase price variance on supplier invoice for " + po.get("number"),
                    "ACCOUNTS_PAYABLE",
                    PRICE_VARIANCE_ACCOUNT,
                    priceVariance.negate());
        s.audit(
                a,
                "INVOICE_MATCHED",
                str(po, "number"),
                number + " · supplier invoice " + reference + ": " + lines(computed.size()) + " · billed "
                        + amount(billed) + (variance ? " · price variance " + amount(priceVariance) : " · no variance")
                        + " · " + String.join("; ", details));
        var out = new LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("number", number);
        out.put("status", variance ? "VARIANCE" : "MATCHED");
        out.put("amount", billed);
        out.put("matchedAmount", matched);
        out.put("priceVariance", priceVariance);
        return out;
    }

    /** Received quantity of a receipt that is neither invoiced nor returned before invoicing. */
    static BigDecimal open(Map<String, Object> receipt) {
        return dec(receipt.get("quantity"))
                .subtract(dec(receipt.get("invoiced")))
                .subtract(dec(receipt.get("returned")).subtract(dec(receipt.get("credited"))));
    }
}
