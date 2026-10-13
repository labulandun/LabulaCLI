package shop;

import shop.model.Order;

public class OrderService {

    public static final double DISCOUNT_THRESHOLD = 100.0;
    public static final double DISCOUNT_RATE = 0.1;

    public double calculateTotal(Order order) {
        double total = order.getUnitPrice() * order.getQuantity();
        if (total >= DISCOUNT_THRESHOLD) {
            total = total * (1 - DISCOUNT_RATE);
        }
        return total;
    }
}
