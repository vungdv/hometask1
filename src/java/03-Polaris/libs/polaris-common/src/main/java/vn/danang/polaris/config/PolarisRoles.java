package vn.danang.polaris.config;

/**
 * Standard system role identifiers across Polaris bounded contexts.
 * <p>
 * Corresponds directly to the Keycloak realm roles provisioned in {@code docker/keycloak/realm-export.json}.
 */
public final class PolarisRoles {

    private PolarisRoles() {
    }

    /**
     * Customer Success role: responsible for managing customer profiles,
     * onboarding, account information, and customer support.
     */
    public static final String CUSTOMER_SUCCESS = "customer-success";

    /**
     * Product Catalog role: responsible for managing catalog hierarchy,
     * categories, product listings, pricing, and merchandising.
     */
    public static final String PRODUCT_CATALOG = "product-catalog";

    /**
     * Inventory role: responsible for warehouse operations, store stock,
     * stock counts, and synchronizing inventory with product tables.
     */
    public static final String INVENTORY = "inventory";

    /**
     * Purchase Management role: responsible for handling purchasing operations,
     * placing and managing customer purchases, orders, and fulfillment.
     */
    public static final String PURCHASE_MANAGEMENT = "purchase-management";

    /**
     * Administrative role: superuser access across all store operations.
     */
    public static final String ADMIN = "admin";
}
