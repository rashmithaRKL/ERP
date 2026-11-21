package lk.dexlanka.erp.reporting;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import lk.dexlanka.erp.platform.Actor;

/**
 * One top-level key of the {@code /api/state} snapshot.
 *
 * @param key JSON key; must be unique across all contributors (checked at startup)
 * @param order position of the key in the JSON object (ascending); see the table in the backend README
 * @param visibility which actors receive the key at all (the key is omitted otherwise)
 * @param loader produces the value; runs inside the snapshot's read-only REPEATABLE READ transaction
 */
public record StateSection(String key, int order, Predicate<Actor> visibility, Function<StateRequest, Object> loader) {

    /** A section every signed-in role receives. */
    public static StateSection of(String key, int order, Function<StateRequest, Object> loader) {
        return new StateSection(key, order, actor -> true, loader);
    }

    /** Restricts the section to actors whose role is exactly one of {@code roles} (ADMIN is not implied). */
    public StateSection visibleToRoles(String... roles) {
        var allowed = Set.of(roles);
        return new StateSection(key, order, actor -> allowed.contains(actor.role()), loader);
    }

    boolean isVisibleTo(Actor actor) {
        return visibility.test(actor);
    }
}
