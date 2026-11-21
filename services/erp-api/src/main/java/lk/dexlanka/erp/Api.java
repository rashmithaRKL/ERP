package lk.dexlanka.erp;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class Api {
    final ManufacturingService mo;final OperationsService ops;final Queries q;final CatalogService catalog;
    Api(ManufacturingService mo,OperationsService ops,Queries q,CatalogService catalog){this.mo=mo;this.ops=ops;this.q=q;this.catalog=catalog;}
    @PostMapping("/users") Map<String,Object> user(Authentication a,@Valid @RequestBody Commands.User c,@RequestHeader("Idempotency-Key") String k) {return catalog.user(Security.actor(a),c,k);}
    @PostMapping("/boms") Map<String,Object> bom(Authentication a,@Valid @RequestBody Commands.Bom c,@RequestHeader("Idempotency-Key") String k) {return catalog.bom(Security.actor(a),c,k);}
    @PostMapping("/boms/{id}/approve") Map<String,Object> approveBom(Authentication a,@PathVariable String id,@RequestHeader("Idempotency-Key") String k) {return catalog.approveBom(Security.actor(a),id,k);}
    @PostMapping("/partners") Map<String,Object> partner(Authentication a,@Valid @RequestBody Commands.Partner c,@RequestHeader("Idempotency-Key") String k) {return catalog.partner(Security.actor(a),c,k);}
    @PostMapping("/machines") Map<String,Object> machine(Authentication a,@Valid @RequestBody Commands.Machine c,@RequestHeader("Idempotency-Key") String k) {return catalog.machine(Security.actor(a),c,k);}
    @PostMapping("/organization") Map<String,Object> organization(Authentication a,@Valid @RequestBody Commands.Organization c,@RequestHeader("Idempotency-Key") String k) {return catalog.organization(Security.actor(a),c,k);}
    @GetMapping("/state") Map<String,Object> state(Authentication a,@RequestParam(defaultValue="14") int days) {return q.state(Security.actor(a),days);}
    @GetMapping("/manufacturing/{id}") Map<String,Object> details(Authentication a,@PathVariable String id) {return q.details(Security.actor(a),id);}
    @PostMapping("/manufacturing") Map<String,Object> manufacture(Authentication a,@Valid @RequestBody Commands.Manufacture c,@RequestHeader("Idempotency-Key") String k) {return mo.create(Security.actor(a),c,k);}
    @PostMapping("/manufacturing/{id}/actions/{action}") Map<String,Object> transition(Authentication a,@PathVariable String id,@PathVariable String action,@RequestHeader("Idempotency-Key") String k) {return mo.transition(Security.actor(a),id,action,k);}
    @PostMapping("/manufacturing/{id}/complete") Map<String,Object> complete(Authentication a,@PathVariable String id,@Valid @RequestBody Commands.Complete c,@RequestHeader("Idempotency-Key") String k) {return mo.complete(Security.actor(a),id,c,k);}
    @PostMapping("/purchases") Map<String,Object> purchase(Authentication a,@Valid @RequestBody Commands.Purchase c,@RequestHeader("Idempotency-Key") String k) {return ops.purchase(Security.actor(a),c,k);}
    @PostMapping("/purchases/{id}/approve") Map<String,Object> approve(Authentication a,@PathVariable String id,@RequestHeader("Idempotency-Key") String k) {return ops.approvePurchase(Security.actor(a),id,k);}
    @PostMapping("/purchases/{id}/receive") Map<String,Object> receive(Authentication a,@PathVariable String id,@Valid @RequestBody Commands.Receive c,@RequestHeader("Idempotency-Key") String k) {return ops.receive(Security.actor(a),id,c,k);}
    @PostMapping("/receipts/{id}/invoice") Map<String,Object> invoice(Authentication a,@PathVariable String id,@Valid @RequestBody Commands.Invoice c,@RequestHeader("Idempotency-Key") String k) {return ops.invoice(Security.actor(a),id,c,k);}
    @PostMapping("/quality/{id}/inspect") Map<String,Object> inspect(Authentication a,@PathVariable String id,@Valid @RequestBody Commands.Inspect c,@RequestHeader("Idempotency-Key") String k) {return ops.inspect(Security.actor(a),id,c,k);}
    @PostMapping("/sales") Map<String,Object> sale(Authentication a,@Valid @RequestBody Commands.Sale c,@RequestHeader("Idempotency-Key") String k) {return ops.sale(Security.actor(a),c,k);}
    @PostMapping("/sales/{id}/dispatch") Map<String,Object> dispatch(Authentication a,@PathVariable String id,@Valid @RequestBody Commands.Dispatch c,@RequestHeader("Idempotency-Key") String k) {return ops.dispatch(Security.actor(a),id,c,k);}
    @PostMapping("/items") Map<String,Object> item(Authentication a,@Valid @RequestBody Commands.Item c,@RequestHeader("Idempotency-Key") String k) {return ops.item(Security.actor(a),c,k);}
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {return ResponseEntity.badRequest().body(Map.of("message",e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()+": "+f.getDefaultMessage()).findFirst().orElse("Invalid input")));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(DataIntegrityViolationException e) {return ResponseEntity.status(409).body(Map.of("message","This transaction violates a record, quantity or accounting constraint. Check for duplicate references and valid units."));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.toString(e.getReason(),"Request could not be completed")));}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ResponseEntity<?> denied(Exception e) {return ResponseEntity.status(403).body(Map.of("message",e.getMessage()));}
}
