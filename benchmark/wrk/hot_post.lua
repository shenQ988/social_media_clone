-- wrk script mirroring benchmark/k6/hot_post_test.js, but using wrk instead
-- of k6 -- a much lighter-weight load generator (single C binary, epoll
-- event loop, no per-VU JS VM), so it competes far less with the backend
-- for CPU when both run on the same machine.
--
-- Usage:
--   wrk -t4 -c100 -d30s -s benchmark/wrk/hot_post.lua \
--       http://localhost:8000/api/v1/posts/9/
--
-- Auth credentials are read from USERNAME/PASSWORD env vars (defaults
-- below), matching the k6 script's approach -- HTTP Basic Auth, since
-- AuthUtil explicitly supports it for non-browser/stateless clients.

local username = os.getenv("USERNAME") or "mkim"
local password = os.getenv("PASSWORD") or "password123"

-- Minimal base64 encoder (LuaJIT's standard library has no built-in one).
local b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local function base64encode(data)
  return ((data:gsub(".", function(x)
    local r, b = "", x:byte()
    for i = 8, 1, -1 do r = r .. (b % 2 ^ i - b % 2 ^ (i - 1) > 0 and "1" or "0") end
    return r
  end) .. "0000"):gsub("%d%d%d?%d?%d?%d?", function(x)
    if (#x < 6) then return "" end
    local c = 0
    for i = 1, 6 do c = c + (x:sub(i, i) == "1" and 2 ^ (6 - i) or 0) end
    return b64chars:sub(c + 1, c + 1)
  end) .. ({ "", "==", "=" })[#data % 3 + 1])
end

local auth_header = "Basic " .. base64encode(username .. ":" .. password)

wrk.method = "GET"
wrk.headers["Authorization"] = auth_header
wrk.headers["User-Agent"] =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " ..
  "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
wrk.headers["Accept"] = "application/json, text/plain, */*"
wrk.headers["Accept-Language"] = "en-US,en;q=0.9"

-- Track non-200 responses; wrk has no built-in "fail the run" concept like
-- k6's thresholds, so the runner script checks this count in the summary
-- output and can treat any non-zero count as a failed run.
local non200 = 0

response = function(status, headers, body)
  if status ~= 200 then
    non200 = non200 + 1
  end
end

done = function(summary, latency, requests)
  io.write("\n=== hot post benchmark (wrk) ===\n")
  io.write(string.format("requests: %d  throughput: %.2f req/s  errors(non-200): %d\n",
    summary.requests, summary.requests / (summary.duration / 1e6), non200))
  io.write(string.format("latency avg: %.2fms  p50: %.2fms  p90: %.2fms  p95: %.2fms  p99: %.2fms  max: %.2fms\n",
    latency.mean / 1000, latency:percentile(50) / 1000, latency:percentile(90) / 1000,
    latency:percentile(95) / 1000, latency:percentile(99) / 1000, latency.max / 1000))
  io.write(string.format("socket errors: connect=%d read=%d write=%d timeout=%d\n",
    summary.errors.connect, summary.errors.read, summary.errors.write, summary.errors.timeout))
end
