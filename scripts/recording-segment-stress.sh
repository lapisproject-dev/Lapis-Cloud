#!/usr/bin/env bash
# V1.9.155 -- diagnostic mirror of FfmpegSegmentStressTest: measures how often the six-camera video segment of the recording
# composition hangs, using only bash + ffmpeg + coreutils (so it runs inside the production image, no server, no real data).
#
# The ffmpeg command mirrors SegmentArgumentBuilder.buildVideoSegment for a 3x2 grid of 184 frames at 30 fps (1280x720). The Kotlin test
# is the source of truth -- keep this script in sync with the builder when the builder changes.
#
#   RUNS=20 TIMEOUT=25 scripts/recording-segment-stress.sh
#   docker run --rm -v "$PWD/scripts:/s" --entrypoint bash <image> /s/recording-segment-stress.sh
#
# Variants: A old command (no -t on the inputs), B production command (-t per input). Fixed paths below a private mktemp directory,
# no input from outside except the two numeric variables.
set -u
RUNS="${RUNS:-20}"
TIMEOUT="${TIMEOUT:-25}"
case "$RUNS$TIMEOUT" in *[!0-9]*) echo "RUNS and TIMEOUT must be integers" >&2; exit 2;; esac
FF="${FFMPEG:-ffmpeg}"
TMO="$(command -v timeout || command -v gtimeout || true)"
[ -n "$TMO" ] || { echo "timeout (coreutils) not found" >&2; exit 2; }
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
"$FF" -version | head -1

for i in 1 2 3 4 5 6; do
  if [ $((i % 2)) -eq 1 ]; then size=1280x720; else size=640x360; fi
  "$FF" -y -nostdin -hide_banner -loglevel error -f lavfi -i "testsrc2=size=$size:rate=30:duration=20" \
    -c:v libvpx -b:v 300k -deadline realtime -cpu-used 8 "$W/c$i.webm" || exit 2
  "$FF" -y -nostdin -hide_banner -loglevel error -copyts -i "$W/c$i.webm" -map 0:v:0 -c copy -f matroska "$W/c$i.mkv" || exit 2
done

# cell geometry: a 3x2 grid on 1280x720 with even cell sizes (the exact production rounding is irrelevant to the hang)
CELLS=("0 0 426 360" "426 0 428 360" "854 0 426 360" "0 360 426 360" "426 360 428 360" "854 360 426 360")

build() { # $1 = A|B, $2 = output
  local variant="$1" out="$2" graph="" prev="0:v" idx=1
  local args=()
  args+=(-y -nostdin -hide_banner -loglevel error -f lavfi -i "color=black:size=1280x720:rate=30:duration=6.133333")
  for i in 1 2 3 4 5 6; do
    read -r x y w h <<<"${CELLS[$((i - 1))]}"
    args+=(-ss 3.000000)
    if [ "$variant" = B ]; then args+=(-t 6.633333); fi
    args+=(-i "$W/c$i.mkv")
    graph+="[$idx:v]fps=fps=30:start_time=0,scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black[v$idx];"
    if [ "$i" -eq 6 ]; then o=vout; else o="ov$i"; fi
    graph+="[$prev][v$idx]overlay=x=$x:y=$y:eof_action=repeat[$o]"
    [ "$i" -lt 6 ] && graph+=";"
    prev="$o"; idx=$((idx + 1))
  done
  args+=(-filter_complex "$graph" -map "[vout]" -an -frames:v 184 -c:v libx264 -preset veryfast -crf 23 -profile:v high -pix_fmt yuv420p
    -r 30 -g 60 -bf 0 -f mp4 "$out")
  ARGS=("${args[@]}")
}

printf '%-22s %5s %9s %7s\n' variant runs timeouts failed
for variant in A B; do
  to=0; bad=0
  for n in $(seq 1 "$RUNS"); do
    build "$variant" "$W/out-$variant.mp4"
    "$TMO" -s KILL "$TIMEOUT" "$FF" "${ARGS[@]}" >/dev/null 2>"$W/err.txt"
    rc=$?
    if [ $rc -eq 137 ]; then to=$((to + 1)); elif [ $rc -ne 0 ]; then bad=$((bad + 1)); fi
  done
  printf '%-22s %5d %9d %7d\n' "$variant" "$RUNS" "$to" "$bad"
  [ "$bad" -gt 0 ] && head -3 "$W/err.txt" >&2
done
