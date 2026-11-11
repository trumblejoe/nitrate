#!/usr/bin/env bash
# Regenerates the speaker walk-through clips for the in-app audio channel test
# (app/src/main/assets/channeltest). In each clip the speakers take turns, in layout order:
# a spoken name then a pink-noise burst on that channel only (the subwoofer gets a 50 Hz tone),
# every other channel silent. SEGMENT must match ChannelTest.SEGMENT_MS in the app.
# Requires ffmpeg built with libflite, and python3 for the isolation check.
#
# Formats are limited by what ffmpeg can encode correctly: its E-AC-3 and TrueHD encoders stop at
# 5.1, so 7.1 is FLAC only (decoded to 8-channel PCM). DTS is left out: ffmpeg's experimental DTS
# encoder leaks surround-right into the LFE channel, which would point users at the wrong speaker.
set -euo pipefail
TOOLS="$(cd "$(dirname "$0")" && pwd)"
OUT="$TOOLS/../app/src/main/assets/channeltest"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
FF=(ffmpeg -hide_banner -loglevel error -y)
SEGMENT=3        # seconds per speaker
RATE=48000

# segment <name> <spoken text | LFE> -> $WORK/<name>.wav, mono, exactly SEGMENT seconds
segment() {
  local name=$1 text=$2
  if [[ $text == LFE ]]; then
    "${FF[@]}" -f lavfi -i "sine=frequency=50:sample_rate=$RATE:duration=2" \
      -af "volume=0.8,afade=t=in:d=0.1,afade=t=out:st=1.9:d=0.1,apad=whole_dur=$SEGMENT" "$WORK/$name.wav"
  else
    "${FF[@]}" -f lavfi -i "flite=text='$text':voice=slt" -af "aresample=$RATE,volume=1.6" "$WORK/$name-voice.wav"
    "${FF[@]}" -f lavfi -i "anoisesrc=color=pink:amplitude=0.35:sample_rate=$RATE:duration=1.2" \
      -af "afade=t=in:d=0.05,afade=t=out:st=1.1:d=0.1" "$WORK/$name-noise.wav"
    "${FF[@]}" -i "$WORK/$name-voice.wav" -f lavfi -i "anullsrc=r=$RATE:cl=mono:d=0.25" -i "$WORK/$name-noise.wav" \
      -filter_complex "[0][1][2]concat=n=3:v=0:a=1,atrim=0:$SEGMENT,apad=whole_dur=$SEGMENT" -ar $RATE "$WORK/$name.wav"
  fi
}

declare -A SPOKEN=(
  [FL]="Front left" [FR]="Front right" [FC]="Center" [LFE]=LFE
  [SL]="Surround left" [SR]="Surround right" [BL]="Rear left" [BR]="Rear right"
)
for ch in "${!SPOKEN[@]}"; do segment "$ch" "${SPOKEN[$ch]}"; done

# walk <layout> <channels...> -> $WORK/walk-<layout>.wav with each channel delayed into its slot
walk() {
  local layout=$1; shift
  local channels=("$@") n=$# inputs=() graph="" i=0
  local total=$((SEGMENT * n))
  for ch in "${channels[@]}"; do
    inputs+=(-i "$WORK/$ch.wav")
    graph+="[$i]adelay=$((i * SEGMENT * 1000)):all=1,apad=whole_dur=$total,atrim=0:$total[c$i];"
    i=$((i + 1))
  done
  local joins="" map=""
  for ((i = 0; i < n; i++)); do joins+="[c$i]"; map+="$i.0-${channels[$i]}|"; done
  "${FF[@]}" "${inputs[@]}" -filter_complex "${graph}${joins}join=inputs=$n:channel_layout=$layout:map=${map%|}" \
    -ar $RATE "$WORK/walk-$layout.wav"
}

walk stereo FL FR
walk "5.1(side)" FL FR FC LFE SL SR
walk 7.1 FL FR FC LFE BL BR SL SR

rm -f "$OUT"/*
cd "$OUT"
"${FF[@]}" -i "$WORK/walk-stereo.wav" -c:a aac -b:a 128k stereo_aac.m4a
# Tag as standard 5.1 so AAC uses channel configuration 6 rather than a custom channel map.
"${FF[@]}" -i "$WORK/walk-5.1(side).wav" -af "channelmap=channel_layout=5.1" -c:a aac -b:a 256k 51_aac.m4a
"${FF[@]}" -i "$WORK/walk-5.1(side).wav" -c:a ac3 -b:a 384k 51_ac3.mka
"${FF[@]}" -i "$WORK/walk-5.1(side).wav" -c:a eac3 -b:a 384k 51_eac3.mka
"${FF[@]}" -i "$WORK/walk-5.1(side).wav" -c:a truehd -strict -2 51_truehd.mka
"${FF[@]}" -i "$WORK/walk-5.1(side).wav" -c:a flac 51_flac.mka
"${FF[@]}" -i "$WORK/walk-7.1.wav" -c:a flac 71_flac.mka

python3 "$TOOLS/check-channel-isolation.py" "$OUT"/*
du -sh "$OUT"
