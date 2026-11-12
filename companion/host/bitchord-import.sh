#!/bin/sh
# BitChord ND companion — host importer.
#
# Imports the albums the companion puts in the incoming folder, one folder at a
# time, with the beets installed on this machine (your config, your library).
# Run by bitchord-import.service whenever a request appears in the queue.
#
# For each <job>.request in $QUEUE (one line: the album's folder name):
#   1. rename it to <job>.working          (the companion sees it was picked up)
#   2. beet -c $OVERLAY import <folder>    (that folder only, never the whole inbox)
#   3. write <job>.log and then <job>.result (code=, imported=, matched=)
set -u

QUEUE="${QUEUE:-$HOME/navidrome/companion/queue}"
INCOMING="${INCOMING:-$HOME/navidrome/incoming_music}"
OVERLAY="${OVERLAY:-$HOME/.config/bitchord-companion/beets-auto.yaml}"
BEET="${BEET:-beet}"

for request in "$QUEUE"/*.request; do
    [ -e "$request" ] || continue
    job=$(basename "$request" .request)
    case "$job" in *[!0-9a-f]*|'') rm -f "$request"; continue ;; esac

    working="$QUEUE/$job.working"
    mv "$request" "$working" || continue
    folder=$(head -n 1 "$working")
    log="$QUEUE/$job.log"

    # One folder directly inside $INCOMING, nothing else.
    case "$folder" in
        ''|.*|*/*)
            echo "Refusing folder name: $folder" > "$log"
            printf 'code=2\nimported=0\nmatched=no\n' > "$QUEUE/.$job.result"
            mv "$QUEUE/.$job.result" "$QUEUE/$job.result"
            rm -f "$working"
            continue
            ;;
    esac

    if [ ! -d "$INCOMING/$folder" ]; then
        echo "No such folder: $INCOMING/$folder" > "$log"
        code=3
    else
        "$BEET" -c "$OVERLAY" import "$INCOMING/$folder" > "$log" 2>&1
        code=$?
    fi

    # Which of this job's tracks are in the library now, and whether beets
    # matched them to a MusicBrainz release or kept them as they came.
    query="comments:bitchord:$job"
    imported=$("$BEET" ls -f '$id' "$query" 2>/dev/null | grep -c .)
    mbid=$("$BEET" ls -f '$mb_albumid' "$query" 2>/dev/null | grep -m 1 .)
    matched=no
    [ -n "$mbid" ] && matched=yes

    printf 'code=%s\nimported=%s\nmatched=%s\n' "$code" "$imported" "$matched" > "$QUEUE/.$job.result"
    mv "$QUEUE/.$job.result" "$QUEUE/$job.result"
    rm -f "$working"

    # An empty album folder left behind by a move is just clutter.
    [ -d "$INCOMING/$folder" ] && rmdir "$INCOMING/$folder" 2>/dev/null
done
exit 0
