"""Load test: simulate a single post going viral (many repeated GETs).

Hits GET /api/v1/posts/<postid>/ concurrently to mimic a large number of
users opening the same post at once. Measures latency, throughput, server
CPU usage, and per-request SQL query count (via the X-DB-Query-Count
header, enabled by running the server with PIXFRAME_QUERY_DEBUG=1).

Usage:
    PIXFRAME_QUERY_DEBUG=1 ./bin/pixframerun   # in one terminal
    python3 loadtest/viral_post_test.py --requests 10000 --concurrency 100

Output is printed as a human-readable report and saved as JSON.
"""
import argparse
import json
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

try:
    import psutil
except ImportError:
    psutil = None


def login(base_url, username, password):
    """Log in and return the session cookie jar."""
    session = requests.Session()
    response = session.post(
        f"{base_url}/api/v1/accounts/login/",
        json={"username": username, "password": password},
        timeout=10,
    )
    response.raise_for_status()
    return session.cookies.get_dict()


def fire_request(base_url, path, cookies):
    """Perform a single timed GET request. Returns a result dict."""
    start = time.perf_counter()
    try:
        response = requests.get(
            f"{base_url}{path}", cookies=cookies, timeout=30
        )
        elapsed = time.perf_counter() - start
        query_count = response.headers.get("X-DB-Query-Count")
        return {
            "elapsed": elapsed,
            "status": response.status_code,
            "query_count": int(query_count) if query_count else None,
        }
    except requests.RequestException as error:
        elapsed = time.perf_counter() - start
        return {"elapsed": elapsed, "status": None, "error": str(error)}


def find_server_process(psutil_module, port):
    """Best-effort lookup of the Flask dev server process by open port."""
    if psutil_module is None:
        return None
    for proc in psutil_module.process_iter(["pid", "name", "cmdline"]):
        try:
            for conn in proc.net_connections(kind="inet"):
                if conn.laddr and conn.laddr.port == port and conn.status == "LISTEN":
                    return proc
        except (psutil_module.AccessDenied, psutil_module.NoSuchProcess):
            continue
        except Exception:  # pylint: disable=broad-except
            continue
    return None


def sample_cpu(psutil_module, proc, samples, stop_event):
    """Background thread: sample CPU% of the server process periodically."""
    if proc is None:
        return
    proc.cpu_percent(interval=None)  # prime the internal counter
    while not stop_event.is_set():
        try:
            samples.append(proc.cpu_percent(interval=0.5))
        except Exception:  # pylint: disable=broad-except
            break


def percentile(sorted_values, pct):
    """Nearest-rank percentile of an already-sorted list."""
    if not sorted_values:
        return None
    idx = min(
        len(sorted_values) - 1, int(round(pct / 100 * len(sorted_values))) - 1
    )
    return sorted_values[max(idx, 0)]


def main():
    """Run the viral-post load test and print/save a report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--postid", type=int, default=9)
    parser.add_argument("--requests", type=int, default=10000)
    parser.add_argument("--concurrency", type=int, default=100)
    parser.add_argument("--username", default="mkim")
    parser.add_argument("--password", default="password123")
    parser.add_argument("--label", default="baseline")
    args = parser.parse_args()

    base_url = f"http://{args.host}:{args.port}"
    path = f"/api/v1/posts/{args.postid}/"

    print(f"Logging in as {args.username}...")
    cookies = login(base_url, args.username, args.password)

    if psutil is None:
        print("psutil not installed -- CPU sampling will be skipped.")
    server_proc = find_server_process(psutil, args.port)
    if psutil is not None and server_proc is None:
        print(f"Could not find a listening process on port {args.port} "
              "-- CPU sampling will be skipped.")

    cpu_samples = []
    stop_event = threading.Event()
    cpu_thread = threading.Thread(
        target=sample_cpu,
        args=(psutil, server_proc, cpu_samples, stop_event),
        daemon=True,
    )
    if server_proc is not None:
        cpu_thread.start()

    print(
        f"Simulating a viral post: {args.requests} requests to {path} "
        f"at concurrency {args.concurrency}..."
    )
    results = []
    wall_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(fire_request, base_url, path, cookies)
            for _ in range(args.requests)
        ]
        for i, future in enumerate(as_completed(futures), 1):
            results.append(future.result())
            if i % 1000 == 0:
                print(f"  {i}/{args.requests} requests completed")
    wall_elapsed = time.perf_counter() - wall_start

    stop_event.set()
    if server_proc is not None:
        cpu_thread.join(timeout=2)

    latencies = sorted(r["elapsed"] for r in results)
    errors = [r for r in results if r.get("status") != 200]
    query_counts = [
        r["query_count"] for r in results if r.get("query_count") is not None
    ]

    report = {
        "label": args.label,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "target": {"path": path, "postid": args.postid},
        "load": {
            "total_requests": args.requests,
            "concurrency": args.concurrency,
        },
        "throughput": {
            "wall_seconds": round(wall_elapsed, 3),
            "requests_per_sec": round(args.requests / wall_elapsed, 2),
        },
        "latency_ms": {
            "min": round(latencies[0] * 1000, 2) if latencies else None,
            "mean": round(statistics.mean(latencies) * 1000, 2)
            if latencies else None,
            "median": round(statistics.median(latencies) * 1000, 2)
            if latencies else None,
            "p95": round(percentile(latencies, 95) * 1000, 2)
            if latencies else None,
            "p99": round(percentile(latencies, 99) * 1000, 2)
            if latencies else None,
            "max": round(latencies[-1] * 1000, 2) if latencies else None,
        },
        "errors": {
            "count": len(errors),
            "rate_pct": round(100 * len(errors) / args.requests, 3),
        },
        "cpu_pct": {
            "avg": round(statistics.mean(cpu_samples), 1)
            if cpu_samples else None,
            "peak": round(max(cpu_samples), 1) if cpu_samples else None,
            "samples": len(cpu_samples),
        },
        "db_queries_per_request": {
            "avg": round(statistics.mean(query_counts), 2)
            if query_counts else None,
            "min": min(query_counts) if query_counts else None,
            "max": max(query_counts) if query_counts else None,
            "total": sum(query_counts) if query_counts else None,
            "sampled_requests": len(query_counts),
        },
    }

    print("\n=== Load test report ===")
    print(json.dumps(report, indent=2))

    if report["db_queries_per_request"]["avg"] is None:
        print(
            "\nNOTE: no X-DB-Query-Count header seen on responses. "
            "Restart the server with PIXFRAME_QUERY_DEBUG=1 to capture "
            "per-request query counts."
        )

    results_dir = Path(__file__).parent / "results"
    results_dir.mkdir(exist_ok=True)
    out_path = results_dir / f"{args.label}_{int(time.time())}.json"
    out_path.write_text(json.dumps(report, indent=2))
    print(f"\nSaved report to {out_path}")


if __name__ == "__main__":
    main()
