/**
 * Hot-post benchmark: simulates a viral post where every virtual user (VU)
 * repeatedly requests the same GET /api/v1/posts/{postid}/ endpoint for the
 * whole test duration.
 *
 * Note on the URL: the actual route is GET /api/v1/posts/{postid}/ (Spring's
 * @GetMapping requires the trailing slash here -- GET /api/v1/posts/9
 * without it 404s). See PostsController#getPostDetails.
 *
 * Auth: this endpoint requires authentication. Rather than log in and manage
 * a session cookie per VU, this script uses HTTP Basic Auth -- AuthUtil
 * explicitly supports it as a fallback for non-browser clients (see
 * backend/src/main/java/com/pixframe/util/AuthUtil.java), which is exactly
 * this use case: many independent, stateless, concurrent clients.
 *
 * Configurable via environment variables (all optional, defaults shown):
 *   BASE_URL   http://localhost:8000
 *   POSTID     9
 *   USERNAME   mkim
 *   PASSWORD   password123
 *   VUS        100
 *   DURATION   30s
 *
 * Usage:
 *   k6 run benchmark/k6/hot_post_test.js
 *   VUS=500 DURATION=60s k6 run benchmark/k6/hot_post_test.js
 *   k6 run --summary-export=benchmark/results/vus_500.json \
 *       -e VUS=500 -e DURATION=60s benchmark/k6/hot_post_test.js
 */
import http from "k6/http";
import { check } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8000";
const POSTID = __ENV.POSTID || "9";
const USERNAME = __ENV.USERNAME || "mkim";
const PASSWORD = __ENV.PASSWORD || "password123";

const VUS = parseInt(__ENV.VUS || "100", 10);
const DURATION = __ENV.DURATION || "30s";

// Basic Auth via userinfo in the URL -- k6/http supports this natively.
const AUTH_URL = `http://${USERNAME}:${PASSWORD}@${BASE_URL.replace(/^https?:\/\//, "")}`;
const TARGET_URL = `${AUTH_URL}/api/v1/posts/${POSTID}/`;

// Realistic browser-like headers, not just k6's bare default UA.
const HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
  Accept: "application/json, text/plain, */*",
  "Accept-Language": "en-US,en;q=0.9",
  Connection: "keep-alive",
};

// Custom metrics, in addition to k6's built-in http_req_duration/http_reqs/http_req_failed.
const nonOkRate = new Rate("hot_post_non_200_rate");
const postLatency = new Trend("hot_post_latency_ms", true);

export const options = {
  scenarios: {
    hot_post: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    // Fails the whole run (non-zero exit code) if any request is not 200.
    hot_post_non_200_rate: ["rate==0"],
    http_req_failed: ["rate==0"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max", "count"],
};

export default function () {
  const response = http.get(TARGET_URL, { headers: HEADERS });

  const ok = check(response, {
    "status is 200": (r) => r.status === 200,
  });

  nonOkRate.add(!ok);
  postLatency.add(response.timings.duration);
}

export function handleSummary(data) {
  // Print a compact console summary in addition to whatever
  // --summary-export=<file>.json the caller requested.
  const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
  const rps = data.metrics.http_reqs
    ? data.metrics.http_reqs.values.rate.toFixed(2)
    : "n/a";
  const p95 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values["p(95)"].toFixed(2)
    : "n/a";
  const p99 = data.metrics.http_req_duration
    ? data.metrics.http_req_duration.values["p(99)"].toFixed(2)
    : "n/a";
  const failRate = data.metrics.http_req_failed
    ? (data.metrics.http_req_failed.values.rate * 100).toFixed(3)
    : "n/a";

  console.log(
    `\n=== hot post benchmark (VUs=${VUS}, duration=${DURATION}) ===\n` +
      `requests: ${reqs}  throughput: ${rps} req/s\n` +
      `p95: ${p95} ms  p99: ${p99} ms  error rate: ${failRate}%\n`,
  );

  return {
    stdout: "", // suppress k6's default giant summary block; we printed our own above
  };
}
