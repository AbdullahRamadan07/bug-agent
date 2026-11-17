// Demo project used by MOCK_MODE previews. It contains a deliberate bug.
export function cartTotal(items) {
  let total = 0;
  for (const item of items) {
    total += item.price;
  }
  return Math.round(total * 100) / 100;
}

export function formatPrice(value, currency = "USD") {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(value);
}
