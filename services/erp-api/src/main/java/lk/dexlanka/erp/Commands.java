package lk.dexlanka.erp;

import java.math.BigDecimal;
import jakarta.validation.constraints.*;

class Commands {
    record User(@NotBlank @Email @Size(max=160) String email,@NotBlank @Size(max=160) String name,@NotBlank @Size(min=16,max=128) String password,@NotBlank @Pattern(regexp="ADMIN|PLANNER|WAREHOUSE|OPERATOR|QUALITY|SALES|VIEWER") String role) {}
    record BomLine(@NotBlank String itemId,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal quantity) {}
    record Bom(@NotBlank String productId,@NotBlank @Size(max=30) String revision,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal outputQty,@NotNull @DecimalMin("0.000001") @DecimalMax("1") @Digits(integer=1,fraction=6) BigDecimal yieldFactor,@NotEmpty @Size(max=100) java.util.List<@jakarta.validation.Valid BomLine> lines) {}
    record Partner(@NotBlank @Size(max=160) String name,@NotBlank @Pattern(regexp="CUSTOMER|SUPPLIER") String type,@Email @Size(max=160) String email,@Min(0) @Max(365) int paymentDays) {}
    record Machine(@NotBlank @Size(max=30) String code,@NotBlank @Size(max=160) String name,@NotBlank @Size(max=80) String kind,@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal hourlyRate) {}
    record Organization(@NotBlank @Size(max=160) String name,@NotBlank @Size(max=120) String factoryName,@NotBlank @Size(max=160) String location) {}
    record Manufacture(@NotBlank String bomId,@NotBlank String machineId,String salesOrderId,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal quantity,@NotBlank @Pattern(regexp="NORMAL|HIGH|URGENT") String priority,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String dueDate) {}
    record Complete(@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal goodQty,@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal scrapQty,@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal labourCost,@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal machineCost,@NotNull @Size(max=2000) String notes) {}
    record Purchase(@NotBlank String supplierId,@NotBlank String itemId,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal quantity,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal unitCost,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String dueDate) {}
    record Receive(@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal quantity,@NotBlank String warehouseId,@NotBlank @Size(max=80) String supplierLot) {}
    record Inspect(@NotBlank @Pattern(regexp="PASSED|FAILED") String result,@NotBlank @Size(max=2000) String notes) {}
    record Sale(@NotBlank String customerId,@NotBlank String productId,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal quantity,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal unitPrice,@NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String dueDate) {}
    record Dispatch(@NotBlank String lotId,@NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal quantity,@NotBlank @Size(max=120) String reference) {}
    record Item(@NotBlank @Size(max=50) String sku,@NotBlank @Size(max=160) String name,@Pattern(regexp="RAW_MATERIAL|FINISHED_GOOD|PACKAGING") @NotNull String type,@Pattern(regexp="kg|pcs") @NotNull String uom,@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal standardCost,@NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal reorderLevel) {}
    record Invoice(@NotBlank @Size(max=80) String reference) {}
}
