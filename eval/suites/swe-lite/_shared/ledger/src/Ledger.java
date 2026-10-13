package ledger;

import java.util.ArrayList;
import java.util.List;

public class Ledger {

    private final List<Entry> entries = new ArrayList<>();

    public void add(Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        entries.add(entry);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public long balanceCents() {
        long total = 0;
        for (Entry entry : entries) {
            total += entry.amountCents();
        }
        return total;
    }

    public List<Entry> byCategory(String category) {
        List<Entry> matched = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.category().equals(category)) {
                matched.add(entry);
            }
        }
        return matched;
    }
}
