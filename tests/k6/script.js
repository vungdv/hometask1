import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.APP_BaseUrl;

export const options = {
  vus: 10000, // 10 virtual users
  duration: "30s", // test runs for 30 seconds
};

export default function () {
  const res = http.get(`${BASE_URL}/weatherforecast`);
  check(res, {
    "status is 200": (r) => r.status === 200,
  });
  sleep(1);
}
