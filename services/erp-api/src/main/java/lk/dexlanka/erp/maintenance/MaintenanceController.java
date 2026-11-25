package lk.dexlanka.erp.maintenance;

import jakarta.validation.Valid;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/maintenance/**}: downtime, maintenance work orders, preventive plans, machines and metrics. */
@RestController
@RequestMapping("/api/maintenance")
class MaintenanceController {
    private final DowntimeService downtime;
    private final WorkOrderService orders;
    private final SparePartService parts;
    private final PlanService plans;
    private final MachineStatusService machines;
    private final MaintenanceQuery query;
    private final MaintenanceMetrics metrics;

    MaintenanceController(
            DowntimeService downtime,
            WorkOrderService orders,
            SparePartService parts,
            PlanService plans,
            MachineStatusService machines,
            MaintenanceQuery query,
            MaintenanceMetrics metrics) {
        this.downtime = downtime;
        this.orders = orders;
        this.parts = parts;
        this.plans = plans;
        this.machines = machines;
        this.query = query;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ work orders

    @GetMapping("/work-orders")
    Map<String, Object> workOrders(
            Authentication a,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String machine,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.workOrders(Actor.of(a), status, type, machine, q, limit, before);
    }

    @GetMapping("/work-orders/{id}")
    Map<String, Object> workOrder(Authentication a, @PathVariable String id) {
        return query.workOrder(Actor.of(a), id);
    }

    @PostMapping("/work-orders")
    Map<String, Object> request(
            Authentication a,
            @Valid @RequestBody MaintenanceCommands.RequestWorkOrder c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.request(Actor.of(a), c, k);
    }

    @PostMapping("/work-orders/{id}/approve")
    Map<String, Object> approve(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Approve c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.approve(Actor.of(a), id, c, k);
    }

    @PostMapping("/work-orders/{id}/schedule")
    Map<String, Object> schedule(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Schedule c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.schedule(Actor.of(a), id, c, k);
    }

    @PostMapping("/work-orders/{id}/start")
    Map<String, Object> start(Authentication a, @PathVariable String id, @RequestHeader("Idempotency-Key") String k) {
        return orders.start(Actor.of(a), id, k);
    }

    @PostMapping("/work-orders/{id}/progress")
    Map<String, Object> progress(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Progress c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.progress(Actor.of(a), id, c, k);
    }

    @PostMapping("/work-orders/{id}/labour")
    Map<String, Object> labour(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Labour c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.labour(Actor.of(a), id, c, k);
    }

    @PostMapping("/work-orders/{id}/parts")
    Map<String, Object> issue(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.IssuePart c,
            @RequestHeader("Idempotency-Key") String k) {
        return parts.issue(Actor.of(a), id, c, k);
    }

    @PostMapping("/work-orders/{id}/parts/{movementId}/return")
    Map<String, Object> returnPart(
            Authentication a,
            @PathVariable String id,
            @PathVariable String movementId,
            @Valid @RequestBody MaintenanceCommands.ReturnPart c,
            @RequestHeader("Idempotency-Key") String k) {
        return parts.returnPart(Actor.of(a), id, movementId, c, k);
    }

    @PostMapping("/work-orders/{id}/complete")
    Map<String, Object> complete(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Complete c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.complete(Actor.of(a), id, c, k);
    }

    @PostMapping("/work-orders/{id}/cancel")
    Map<String, Object> cancel(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Cancel c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.cancel(Actor.of(a), id, c, k);
    }

    // ------------------------------------------------------------------ plans

    @GetMapping("/plans")
    Map<String, Object> plans(
            Authentication a,
            @RequestParam(required = false) String machine,
            @RequestParam(required = false) String due,
            @RequestParam(required = false) Integer horizon,
            @RequestParam(required = false) String active,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.plans(Actor.of(a), machine, due, horizon, active, limit, before);
    }

    @GetMapping("/plans/{id}")
    Map<String, Object> plan(Authentication a, @PathVariable String id) {
        return query.plan(Actor.of(a), id);
    }

    @PostMapping("/plans")
    Map<String, Object> createPlan(
            Authentication a,
            @Valid @RequestBody MaintenanceCommands.Plan c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.create(Actor.of(a), c, k);
    }

    @PostMapping("/plans/generate")
    Map<String, Object> generate(
            Authentication a,
            @Valid @RequestBody MaintenanceCommands.Generate c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.generate(Actor.of(a), c, k);
    }

    @PostMapping("/plans/{id}")
    Map<String, Object> updatePlan(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.PlanUpdate c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.update(Actor.of(a), id, c, k);
    }

    // ------------------------------------------------------------------ downtime

    @GetMapping("/downtime")
    Map<String, Object> downtime(
            Authentication a,
            @RequestParam(required = false) String machine,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean history,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.downtime(Actor.of(a), machine, reason, from, to, status, history, limit, before);
    }

    @PostMapping("/downtime")
    Map<String, Object> report(
            Authentication a,
            @Valid @RequestBody MaintenanceCommands.ReportDowntime c,
            @RequestHeader("Idempotency-Key") String k) {
        return downtime.report(Actor.of(a), c, k);
    }

    @PostMapping("/downtime/{id}/close")
    Map<String, Object> close(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.CloseDowntime c,
            @RequestHeader("Idempotency-Key") String k) {
        return downtime.close(Actor.of(a), id, c, k);
    }

    @PostMapping("/downtime/{id}/correct")
    Map<String, Object> correct(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.CorrectDowntime c,
            @RequestHeader("Idempotency-Key") String k) {
        return downtime.correct(Actor.of(a), id, c, k);
    }

    // ------------------------------------------------------------------ machines, metrics, settings

    @GetMapping("/machines")
    Map<String, Object> machines(Authentication a) {
        return query.machines(Actor.of(a));
    }

    @GetMapping("/machines/{id}")
    Map<String, Object> machine(Authentication a, @PathVariable String id) {
        return query.machine(Actor.of(a), id);
    }

    @GetMapping("/machines/{id}/history")
    Map<String, Object> history(
            Authentication a,
            @PathVariable String id,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String before) {
        return query.history(Actor.of(a), id, limit, before);
    }

    @PostMapping("/machines/{id}/status")
    Map<String, Object> status(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.MachineStatus c,
            @RequestHeader("Idempotency-Key") String k) {
        return machines.correct(Actor.of(a), id, c, k);
    }

    @PostMapping("/machines/{id}/meter")
    Map<String, Object> meter(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody MaintenanceCommands.Meter c,
            @RequestHeader("Idempotency-Key") String k) {
        return plans.meter(Actor.of(a), id, c, k);
    }

    @GetMapping("/metrics")
    Map<String, Object> metrics(
            Authentication a,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String machine) {
        return metrics.metrics(Actor.of(a), from, to, machine);
    }

    @GetMapping("/spare-parts")
    Map<String, Object> spareParts(Authentication a) {
        return query.spareParts(Actor.of(a));
    }

    @GetMapping("/technicians")
    Map<String, Object> technicians(Authentication a) {
        return query.technicians(Actor.of(a));
    }

    @GetMapping("/settings")
    Map<String, Object> settings(Authentication a) {
        return query.settings(Actor.of(a));
    }

    @PostMapping("/settings")
    Map<String, Object> updateSettings(
            Authentication a,
            @Valid @RequestBody MaintenanceCommands.Settings c,
            @RequestHeader("Idempotency-Key") String k) {
        return orders.settings(Actor.of(a), c, k);
    }
}
