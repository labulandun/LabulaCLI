package shop;

import java.util.HashMap;
import java.util.Map;

public class InventoryService {

    private final Map<String, Integer> stock = new HashMap<>();

    // Returns true when the requested quantity is available,
    // and reserves it by decreasing the stock counter.
    public synchronized boolean checkStock(String sku, int quantity) {
        int available = stock.getOrDefault(sku, 0);
        if (available < quantity) {
            return false;
        }
        stock.put(sku, available - quantity);
        return true;
    }

    public synchronized void restock(String sku, int quantity) {
        stock.merge(sku, quantity, Integer::sum);
    }
}
