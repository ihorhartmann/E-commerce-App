#!/bin/sh
# Gives the dlt-redrive consumer group a bookmark at the start of every dead-letter topic that has none yet, so its lag
# in each DLT is the number of messages there that wait for a decision. Nothing ever consumes with this group: whoever
# redrives or discards a message moves its offset past it by hand (see CLAUDE.md), and the lag drops by one.
#
# A DLT the group already has an offset for keeps it - that offset is the bookmark of what has been handled - so this
# can run on every start. It runs once the services are up, as they declare their DLTs at startup.
set -eu

bootstrap="kafka:9092"
group="dlt-redrive"
bin="/opt/kafka/bin"

known_topics=$("$bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" --describe --group "$group" 2>/dev/null \
  | awk -v group="$group" '$1 == group { print $2 }' | sort -u)

for topic in $("$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --list | grep '\.DLT$'); do
  if echo "$known_topics" | grep -qx "$topic"; then
    echo "$topic: keeps its bookmark"
    continue
  fi
  "$bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" --group "$group" --topic "$topic" \
    --reset-offsets --to-earliest --execute > /dev/null
  echo "$topic: bookmark set at the earliest message"
done
