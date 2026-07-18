#!/bin/bash
#
# setup_test_users.sh
#
# Creates a pool of throwaway test accounts (loadtest001..loadtestNNN) so
# the "viral post" benchmark can simulate genuinely distinct viewers
# instead of one single account hammering the endpoint. See the
# discussion in benchmark/results/pre-redis/REPORT.md: with every wrk
# connection authenticating as the same user, PostDetailCache (keyed on
# postid+logname) only ever exercised a single cache key -- not
# representative of many different people viewing the same viral post.
#
# Usernames/passwords are deterministic (loadtestNNN / loadtestpass123)
# so benchmark/wrk/hot_post.lua can regenerate the same identities without
# needing to read a shared file.
#
# Usage: ./benchmark/setup_test_users.sh [pool_size]

set -Eeuo pipefail

POOL_SIZE="${1:-100}"
BASE_URL="${BASE_URL:-http://localhost:8000}"
AVATAR="/tmp/loadtest_avatar.gif"

if ! curl -sf -o /dev/null "${BASE_URL}/actuator/health"; then
    echo "Error: backend not reachable at ${BASE_URL}/actuator/health"
    exit 1
fi

# Tiny valid GIF, reused as every throwaway account's avatar.
printf 'GIF89a' > "$AVATAR"

created=0
skipped=0

for i in $(seq 1 "$POOL_SIZE"); do
    username=$(printf "loadtest%03d" "$i")
    email="${username}@example.com"

    status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}/api/v1/accounts/" \
        -F "fullname=Load Test ${i}" \
        -F "username=${username}" \
        -F "email=${email}" \
        -F "password=loadtestpass123" \
        -F "file=@${AVATAR};filename=avatar.gif;type=image/gif")

    if [ "$status" = "201" ]; then
        created=$((created + 1))
    elif [ "$status" = "409" ]; then
        skipped=$((skipped + 1))
    else
        echo "Unexpected status $status creating ${username}"
    fi
done

echo "Done: ${created} accounts created, ${skipped} already existed (pool size: ${POOL_SIZE})"
