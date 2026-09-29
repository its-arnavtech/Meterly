#!/bin/bash
# ---------------------------------------------------------------------------------------------------------
# Idempotent topic bootstrap for the Kafka API (Redpanda). Safe to run on every `docker compose up`:
#   * missing topic                 -> created with the desired partition count and config
#   * existing topic, fewer parts   -> partitions added (Kafka can only grow partitions, never shrink)
#   * existing topic, enough parts  -> left alone
# Records are keyed by tenant_id, so the partition count caps consumer parallelism per consumer group.
# ---------------------------------------------------------------------------------------------------------
set -euo pipefail

BROKERS="${KAFKA_BROKERS:-redpanda:9092}"
REPLICAS="${TOPIC_REPLICAS:-1}"
DAY_MS=86400000

rpk_() { rpk -X brokers="$BROKERS" "$@"; }

echo "redpanda-init: waiting for brokers at ${BROKERS}"
for attempt in $(seq 1 60); do
    if rpk_ cluster info >/dev/null 2>&1; then break; fi
    if [ "$attempt" -eq 60 ]; then echo "redpanda-init: brokers not reachable" >&2; exit 1; fi
    sleep 2
done

ensure_topic() {
    local name="$1" partitions="$2" retention_days="$3"
    local current
    current="$(rpk_ topic list | awk -v t="$name" '$1 == t { print $2 }')"
    if [ -z "$current" ]; then
        rpk_ topic create "$name" -p "$partitions" -r "$REPLICAS" \
            -c cleanup.policy=delete -c retention.ms=$((retention_days * DAY_MS))
        echo "redpanda-init: created ${name} (${partitions} partitions, ${retention_days}d retention)"
    elif [ "$current" -lt "$partitions" ]; then
        rpk_ topic add-partitions "$name" --num $((partitions - current))
        echo "redpanda-init: grew ${name} from ${current} to ${partitions} partitions"
    else
        echo "redpanda-init: ${name} already exists (${current} partitions)"
    fi
}

# A DLT mirrors its source topic's partition count: Spring Kafka's DeadLetterPublishingRecoverer sends a
# failed record to the *same partition number* on the DLT by default.
# name                       partitions  retention (days)
ensure_topic usage-events              12  7
ensure_topic usage-events.DLT          12  14
ensure_topic notification-events        6  7
ensure_topic notification-events.DLT    6  14

rpk_ topic list
