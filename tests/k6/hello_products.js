import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.GO_APP_BaseUrl;

export const options = {
  vus: 100, // 10 virtual users
  duration: "30s", // test runs for 30 seconds
};

export default function () {
  const res = http.get(`${BASE_URL}/products/?skip=19&limit=15`);

  // Parse JSON response
  const data = res.json();
  // Validate the response structure
  check(res, {
    "status is 200": (r) => r.status === 200,
    "contains products": () => Array.isArray(data.products),
    "has at least 1 product": () => data.products.length == 15,
    "first product has ID": () => data.products[0]?.id === 30,
    "first product has title": () =>
      typeof data.products[0]?.title === "string",
  });
  sleep(1);
}
