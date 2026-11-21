package lk.dexlanka.erp.reporting;

import lk.dexlanka.erp.platform.Actor;

/** Input of every {@code /api/state} section: the signed-in actor and the validated reporting period in days. */
public record StateRequest(Actor actor, int days) {
    public String tenant() {
        return actor.tenant();
    }
}
