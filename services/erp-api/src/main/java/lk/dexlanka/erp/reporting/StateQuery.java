package lk.dexlanka.erp.reporting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.dexlanka.erp.platform.Actor;
import lk.dexlanka.erp.platform.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code GET /api/state} workspace snapshot: every {@link StateSection} of every {@link StateContributor}, in
 * ascending {@link StateSection#order()}, read from one repeatable-read snapshot.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StateQuery {
    private final List<StateSection> sections;

    StateQuery(List<StateContributor> contributors) {
        var all = new ArrayList<StateSection>();
        for (var contributor : contributors) all.addAll(contributor.sections());
        all.sort(Comparator.comparingInt(StateSection::order).thenComparing(StateSection::key));
        var keys = new HashSet<String>();
        for (var section : all) {
            if (!keys.add(section.key()))
                throw new IllegalStateException("Duplicate /api/state section key: " + section.key());
        }
        this.sections = List.copyOf(all);
    }

    public Map<String, Object> state(Actor a, int days) {
        Store.require(Set.of(7, 14, 30, 90).contains(days), "Choose a supported reporting period");
        var request = new StateRequest(a, days);
        var data = new LinkedHashMap<String, Object>();
        for (var section : sections) {
            if (section.isVisibleTo(a)) data.put(section.key(), section.loader().apply(request));
        }
        return data;
    }
}
