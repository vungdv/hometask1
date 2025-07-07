import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.GO_APP_BaseUrl;

export const options = {
  vus: 10, // 10 virtual users
  duration: "1s", // test runs for 30 seconds
};

export default function () {
  const res = http.get(`${BASE_URL}/products/?skip=10&limit=10`);
  check(res, {
    "status is 200": (r) => r.status === 200,
    "response is a valid product json": (r) => {
      try {
        const data = JSON.parse(r.body);
        return (
          Array.isArray(data.products) &&
          data.products.length > 0 &&
          data.products[0].hasOwnProperty("title")
        );
      } catch {
        return false;
      }
    },
  });
  sleep(1);
}
