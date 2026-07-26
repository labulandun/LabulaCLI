package shop.model;

public class Order {

    private final String sku;
    private final double unitPrice;
    private final int quantity;

    public Order(String sku, double unitPrice, int quantity) {
        this.sku = sku;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public String getSku() {
        return sku;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }
}
