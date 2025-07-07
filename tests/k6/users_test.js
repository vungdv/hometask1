import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.APP_BaseUrl;

export const options = {
  vus: 10, // 5 virtual users
  duration: "1s", // run the test for 1 seconds
};

export default function () {
  const res = http.get(`${BASE_URL}/users`);
  check(res, {
    "status is 200": (r) => r.status === 200,
    "response is a valid json user": (r) => {
      try {
        const data = JSON.parse(r.body);
        return (
          Array.isArray(data.users) &&
          data.users.length > 0 &&
          data.users[0].hasOwnProperty("id") &&
          data.users[0].hasOwnProperty("name")
        );
      } catch {
        return false;
      }
    },
  });
  sleep(1);
}
