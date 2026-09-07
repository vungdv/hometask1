"""
Polaris MCP Server - Product Details Tool
Retrieves full details and stock status for a product by its SKU.
"""

from ..client import http_get


def tool_get_product_by_sku(sku: str) -> str:
    """Retrieves full details for a product by its SKU code."""
    cleaned_sku = sku.strip() if sku else ""
    result = http_get(f"/api/v1/products/sku/{cleaned_sku}")
    if "error" in result:
        return f"Error retrieving product '{sku}': {result['error']}"

    stock_status = (
        f"In Stock ({result.get('stockQuantity', 0)} available)"
        if result.get("isAvailable")
        else "OUT OF STOCK"
    )
    return (
        f"Product Details for {result.get('name')}:\n"
        f"- SKU: {result.get('sku')}\n"
        f"- Category: {result.get('category')}\n"
        f"- Price: ${result.get('price')}\n"
        f"- Status: {stock_status}\n"
        f"- Description: {result.get('description', 'No description provided')}"
    )
