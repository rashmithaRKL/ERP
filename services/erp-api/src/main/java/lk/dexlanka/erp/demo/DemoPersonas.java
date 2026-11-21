package lk.dexlanka.erp.demo;

import lk.dexlanka.erp.platform.Actor;

/**
 * The fictional users that {@link DemoSeed} creates in the {@value #TENANT} tenant (sign in through {@code POST
 * /api/auth/demo?role=...}). Feature demo seeds post their commands as these actors so that audit trails, approvals
 * and self-approval rules behave exactly as in real use.
 */
public final class DemoPersonas {
    public static final String TENANT = "demo";
    public static final Actor ADMIN =
            new Actor("demo-admin", TENANT, "demo.admin@dexlanka.test", "Nimal Perera", "ADMIN");
    public static final Actor PLANNER =
            new Actor("demo-planner", TENANT, "demo.planner@dexlanka.test", "Dilini Fernando", "PLANNER");
    public static final Actor OPERATOR =
            new Actor("demo-operator", TENANT, "demo.operator@dexlanka.test", "Kasun Silva", "OPERATOR");
    public static final Actor WAREHOUSE =
            new Actor("demo-warehouse", TENANT, "demo.warehouse@dexlanka.test", "Amal Jayasinghe", "WAREHOUSE");
    public static final Actor QUALITY =
            new Actor("demo-quality", TENANT, "demo.quality@dexlanka.test", "Tharushi Senanayake", "QUALITY");

    private DemoPersonas() {}
}
