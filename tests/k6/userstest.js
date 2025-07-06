import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.APP_BaseUrl;

export const options = {
  vus: 10, // 5 virtual users
  duration: "1d", // run the test for 30 seconds
};

export default function () {
  const res = http.get(`${BASE_URL}/users`);
  // Parse JSON response
  const data = res.json();

  check(res, {
    "status is 200": (r) => r.status === 200,
    "contains users": () => Array.isArray(data.users),
    "has at least 2 users": () => data.users.length >= 2,
    "first user has ID": () => data.users[0]?.id === 1,
    "first user has name": () => data.users[0]?.name === "Alice",
  });
  sleep(1);
}
