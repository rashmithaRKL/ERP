package lk.dexlanka.erp.reporting;

import java.util.List;

/**
 * Implemented by one {@code @Component} per domain package to add its keys to {@code GET /api/state}. {@link
 * StateQuery} collects every contributor bean; adding a section never requires editing another package.
 *
 * <p>Do not annotate contributors with {@code @Transactional}: their loaders already run inside the snapshot
 * transaction of {@link StateQuery}. Keep sections bounded (LIMIT) and prefer a feature's own paginated GET endpoint
 * for anything that can grow.
 */
public interface StateContributor {
    List<StateSection> sections();
}
