-- wrk script mirroring benchmark/k6/hot_post_test.js, but using wrk instead
-- of k6 -- a much lighter-weight load generator (single C binary, epoll
-- event loop, no per-VU JS VM), so it competes far less with the backend
-- for CPU when both run on the same machine.
--
-- Multi-user mode (default): rotates Basic Auth credentials across a pool
-- of throwaway accounts (loadtest001..loadtestNNN, created via
-- benchmark/setup_test_users.sh) so the benchmark simulates genuinely
-- distinct viewers of the same "viral" post, instead of one single
-- account hammering the endpoint. This matters because PostDetailCache is
-- keyed on (postid, logname): a single-user benchmark only ever exercises
-- one cache key, which trivially inflates the hit rate and never exercises
-- the invalidation tracking set beyond one member. See the discussion in
-- benchmark/results/pre-redis/REPORT.md.
--
-- Set POOL_SIZE=1 (and optionally USERNAME/PASSWORD) to fall back to the
-- original single-user behavior.
--
-- Usage:
--   wrk -t4 -c100 -d30s -s benchmark/wrk/hot_post.lua \
--       http://localhost:8000/api/v1/posts/9/

local pool_size = tonumber(os.getenv("POOL_SIZE")) or 100
local pool_password = os.getenv("POOL_PASSWORD") or "loadtestpass123"
local single_username = os.getenv("USERNAME") or "mkim"
local single_password = os.getenv("PASSWORD") or "password123"

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

-- Precompute one Authorization header per pool user (or just one, in
-- single-user mode) -- avoids re-doing base64 work on every request,
-- which would add non-representative load-generator overhead.
local auth_headers = {}
if pool_size <= 1 then
  auth_headers[1] = "Basic " .. base64encode(single_username .. ":" .. single_password)
else
  for i = 1, pool_size do
    local username = string.format("loadtest%03d", i)
    auth_headers[i] = "Basic " .. base64encode(username .. ":" .. pool_password)
  end
end

local common_headers = {
  ["User-Agent"] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " ..
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
  ["Accept"] = "application/json, text/plain, */*",
  ["Accept-Language"] = "en-US,en;q=0.9",
}

-- Round-robin cursor. wrk runs each thread in its own Lua VM (separate
-- globals per thread), so this is naturally thread-local -- no shared
-- counter/locking needed across threads.
local cursor = 0

request = function()
  cursor = (cursor % #auth_headers) + 1
  local headers = {}
  for k, v in pairs(common_headers) do headers[k] = v end
  headers["Authorization"] = auth_headers[cursor]
  return wrk.format("GET", nil, headers, nil)
end

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
  io.write(string.format("pool_size: %d\n", #auth_headers))
  io.write(string.format("requests: %d  throughput: %.2f req/s  errors(non-200): %d\n",
    summary.requests, summary.requests / (summary.duration / 1e6), non200))
  io.write(string.format("latency avg: %.2fms  p50: %.2fms  p90: %.2fms  p95: %.2fms  p99: %.2fms  max: %.2fms\n",
    latency.mean / 1000, latency:percentile(50) / 1000, latency:percentile(90) / 1000,
    latency:percentile(95) / 1000, latency:percentile(99) / 1000, latency.max / 1000))
  io.write(string.format("socket errors: connect=%d read=%d write=%d timeout=%d\n",
    summary.errors.connect, summary.errors.read, summary.errors.write, summary.errors.timeout))
end
