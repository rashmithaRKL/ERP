package lk.dexlanka.erp.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A country tax template ("tax adapter with effective versions"): the default tax codes of a country, their rate
 * history and the default rounding and price convention. An administrator applies a template to the tenant through
 * {@link TaxAdminService#applyTemplate}; applying is idempotent and never overwrites a code the tenant has edited.
 *
 * <p>Templates are <b>a starting point to be verified by a qualified accountant</b>; no certification is claimed. Each
 * implementation is a Spring bean, so a new country is one new class.
 */
public interface TaxRegime {
    String DISCLAIMER = "A starting point to be verified by a qualified accountant; no certification is claimed.";

    /** Short regime code stored on the tenant settings and on template codes, e.g. {@code LK}. */
    String code();

    /** Country name shown in the UI. */
    String country();

    /** Template title shown in the UI, e.g. "Sri Lanka VAT". */
    String name();

    /** {@code PER_LINE} or {@code PER_DOCUMENT}. */
    String rounding();

    /** Whether document prices are entered including tax by default. */
    boolean pricesIncludeTax();

    /** Short notes on what the template covers and what it deliberately leaves out. */
    List<String> notes();

    List<TemplateCode> codes();

    /**
     * One default code. {@code kind} is {@code STANDARD}, {@code ZERO_RATED} or {@code EXEMPT}; {@code appliesTo} is
     * {@code SALES}, {@code PURCHASES} or {@code BOTH}. The flags mark the code the template proposes as the tenant's
     * default sales, default purchase and exemption code.
     */
    record TemplateCode(
            String code,
            String name,
            String appliesTo,
            String kind,
            List<TemplateComponent> components,
            boolean salesDefault,
            boolean purchaseDefault,
            boolean exemptionCode) {}

    /** A component and its rate history (each rate applies from its date until the next one). */
    record TemplateComponent(String name, boolean compound, List<TemplateRate> rates) {}

    record TemplateRate(LocalDate effectiveFrom, BigDecimal rate, String note) {}
}
