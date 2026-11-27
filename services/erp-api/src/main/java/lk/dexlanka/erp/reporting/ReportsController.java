package lk.dexlanka.erp.reporting;

import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Costs & reports (V9, ADMIN and PLANNER; V24 FINANCE): the paged manufacturing cost register and posted journal. */
@RestController
@RequestMapping("/api/reports")
class ReportsController {
    private final ReportQuery reports;

    ReportsController(ReportQuery reports) {
        this.reports = reports;
    }

    @GetMapping("/costs")
    Map<String, Object> costs(
            Authentication a,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return reports.costs(Actor.of(a), status, q, limit, before);
    }

    @GetMapping("/journal")
    Map<String, Object> journal(
            Authentication a,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String postedFrom,
            @RequestParam(required = false) String postedTo,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before) {
        return reports.journal(Actor.of(a), account, q, from, to, postedFrom, postedTo, source, limit, before);
    }
}
