"""
Polaris MCP Server - Product Search Tool
Searches catalog for available products matching query, category, and price range.
"""

from typing import Optional
from ..client import http_get


def tool_search_available_products(
    query: Optional[str] = None,
    category: Optional[str] = None,
    min_price: Optional[float] = None,
    max_price: Optional[float] = None,
    available_only: bool = True,
) -> str:
    """Searches for products in the catalog with availability, category, and price filtering."""
    params = {
        "query": query,
        "category": category,
        "minPrice": min_price,
        "maxPrice": max_price,
        "available": available_only if available_only is not None else True,
    }
    result = http_get("/api/v1/products", params)
    if "error" in result:
        return f"Error querying products: {result['error']}"

    products = result.get("content", [])
    if not products:
        return "No products found matching the specified criteria."

    lines = [f"Found {result.get('totalElements', len(products))} product(s):"]
    for p in products:
        stock_status = (
            f"{p.get('stockQuantity', 0)} in stock"
            if p.get("isAvailable")
            else "Out of stock"
        )
        lines.append(
            f"- [{p.get('sku')}] {p.get('name')} | Category: {p.get('category', 'General')} | "
            f"Price: ${p.get('price')} | Availability: {stock_status}"
        )
        if p.get("description"):
            lines.append(f"  Description: {p.get('description')}")

    return "\n".join(lines)
