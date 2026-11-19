#!/usr/bin/env bash
# Generates the TV & receiver check clips shipped inside the Nitrate Companion plugin
# (companion/Nitrate.Companion/TestClips, served at /Nitrate/TestClips). Each is ~20 s, long
# enough for a TV to switch modes and show its HDR or Dolby Vision badge, or for a receiver to
# lock on and show the sound format. The pictures are test bars with a label saying what to look
# for; the sound clips name each speaker on screen as it plays in turn.
#
# Requires ffmpeg (libx264, libx265; its experimental DTS and TrueHD encoders), dovi_tool
# (https://github.com/quietvoid/dovi_tool) and Dolby's mp4muxer
# (https://github.com/DolbyLaboratories/dlb_mp4base), on PATH or as DOVI_TOOL=... MP4MUXER=... .
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/companion/Nitrate.Companion/TestClips"
DOVI_TOOL="${DOVI_TOOL:-$(command -v dovi_tool)}"
MP4MUXER="${MP4MUXER:-$(command -v mp4muxer)}"
FONT="${FONT:-/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
FF=(ffmpeg -hide_banner -loglevel error -y)
D=20
FPS=24
FRAMES=$((D * FPS))

# Text drawn over the bars: a title and a line of what to look for.
label() { # size title hint
  local s=$1 t=$2 h=$3
  echo "drawbox=x=0:y=ih*0.62:w=iw:h=ih*0.3:color=black@0.72:t=fill,drawtext=fontfile=$FONT:text='$t':fontcolor=white:fontsize=h*0.075:x=(w-tw)/2:y=h*0.66,drawtext=fontfile=$FONT:text='$h':fontcolor=0xDDDDDD:fontsize=h*0.036:x=(w-tw)/2:y=h*0.78,drawtext=fontfile=$FONT:text='Nitrate TV check':fontcolor=0xAAAAAA:fontsize=h*0.03:x=w*0.03:y=h*0.03"
}
bars() { echo "smptehdbars=size=$1:rate=$FPS:duration=$D"; }

HDR10_PARAMS="log-level=error:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"

# HDR10: the TV should show its HDR badge or switch to its HDR picture mode.
"${FF[@]}" -f lavfi -i "$(bars 3840x2160),$(label 4k 'HDR10 · 4K · 10-bit' 'Your TV should show HDR or switch to its HDR picture mode')" \
  -c:v libx265 -pix_fmt yuv420p10le -crf 34 -tag:v hvc1 -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc \
  -x265-params "$HDR10_PARAMS" -an "$OUT/hdr10.mp4"

# HLG.
"${FF[@]}" -f lavfi -i "$(bars 3840x2160),$(label 4k 'HLG · 4K · 10-bit' 'Your TV should show HLG (or HDR) or switch to its HDR picture mode')" \
  -c:v libx265 -pix_fmt yuv420p10le -crf 34 -tag:v hvc1 -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc \
  -x265-params "log-level=error:repeat-headers=1:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" -an "$OUT/hlg.mp4"

# Dolby Vision 8.1 (HDR10 underneath) and 5 (no base layer: wrong colours unless Dolby Vision is processed).
"${FF[@]}" -f lavfi -i "$(bars 3840x2160),$(label 4k 'Dolby Vision 8.1 · 4K' 'A Dolby Vision TV shows its Dolby Vision badge; others show HDR10')" \
  -c:v libx265 -pix_fmt yuv420p10le -crf 34 -x265-params "$HDR10_PARAMS" -f hevc "$WORK/base81.hevc"
"${FF[@]}" -f lavfi -i "$(bars 3840x2160),$(label 4k 'Dolby Vision 5 · 4K' 'Dolby Vision badge, normal colours. Green or purple: not processed')" \
  -c:v libx265 -pix_fmt yuv420p10le -crf 34 -x265-params "log-level=error:repeat-headers=1:colorprim=2:transfer=2:colormatrix=2" -f hevc "$WORK/base5.hevc"
for profile in 8.1 5; do
  cat > "$WORK/rpu-$profile.json" <<JSON
{ "cm_version": "V40", "profile": "$profile", "length": $FRAMES,
  "level6": { "max_display_mastering_luminance": 1000, "min_display_mastering_luminance": 1, "max_content_light_level": 1000, "max_frame_average_light_level": 400 } }
JSON
  "$DOVI_TOOL" generate -j "$WORK/rpu-$profile.json" -o "$WORK/rpu-$profile.bin" >/dev/null
done
"$DOVI_TOOL" inject-rpu -i "$WORK/base81.hevc" --rpu-in "$WORK/rpu-8.1.bin" -o "$WORK/dv81.hevc" >/dev/null
"$DOVI_TOOL" inject-rpu -i "$WORK/base5.hevc" --rpu-in "$WORK/rpu-5.bin" -o "$WORK/dv5.hevc" >/dev/null
"$MP4MUXER" -i "$WORK/dv81.hevc" -o "$OUT/dolby_vision_81.mp4" --dv-profile 8 --dv-bl-compatible-id 1 --mpeg4-comp-brand mp42,iso6,isom,msdh,dby1 --overwrite >/dev/null
"$MP4MUXER" -i "$WORK/dv5.hevc" -o "$OUT/dolby_vision_5.mp4" --dv-profile 5 --mpeg4-comp-brand mp42,iso6,isom,msdh,dby1 --overwrite >/dev/null

# Sound: each speaker in turn (1.5 s each), named on screen, over a plain picture.
# Slots: front left, centre, front right, right surround, left surround, (back right, back left,) subwoofer.
sound_clip() { # file title hint layout codec-args...
  local file=$1 title=$2 hint=$3 layout=$4; shift 4
  local names order n
  if [[ $layout == 7.1 ]]; then
    # 7.1: FL FR FC LFE BL BR SL SR
    names=("Front left" "Centre" "Front right" "Right surround" "Right back" "Left back" "Left surround" "Subwoofer")
    order=(0 2 1 7 5 4 6 3)
  else
    # 5.1: FL FR FC LFE BL BR (as surrounds)
    names=("Front left" "Centre" "Front right" "Right surround" "Left surround" "Subwoofer")
    order=(0 2 1 5 4 3)
  fi
  n=${#names[@]}
  local cycle; cycle=$(echo "$n * 1.5" | bc)
  # One expression per channel: a tone while its slot is on (the subwoofer lower).
  local exprs=() slot
  for ((ch = 0; ch < n; ch++)); do
    for ((slot = 0; slot < n; slot++)); do [[ ${order[$slot]} -eq $ch ]] && break; done
    local f=440; [[ ${names[$slot]} == Subwoofer ]] && f=60
    exprs+=("0.5*sin(2*PI*$f*t)*between(mod(t\\,$cycle)\\,$slot*1.5\\,$slot*1.5+1.1)")
  done
  local IFS='|'
  local audio="aevalsrc=exprs=${exprs[*]}:s=48000:d=$D:c=$layout"
  unset IFS
  local draws="" i
  for ((i = 0; i < n; i++)); do
    draws+=",drawtext=fontfile=$FONT:text='Now\: ${names[$i]}':fontcolor=0xFFD54F:fontsize=h*0.06:x=(w-tw)/2:y=h*0.42:enable='between(mod(t\\,$cycle)\\,$i*1.5\\,$i*1.5+1.5)'"
  done
  "${FF[@]}" -f lavfi -i "color=c=0x101820:size=1920x1080:rate=$FPS:duration=$D$draws,drawtext=fontfile=$FONT:text='$title':fontcolor=white:fontsize=h*0.075:x=(w-tw)/2:y=h*0.2,drawtext=fontfile=$FONT:text='$hint':fontcolor=0xDDDDDD:fontsize=h*0.036:x=(w-tw)/2:y=h*0.62,drawtext=fontfile=$FONT:text='Nitrate receiver check':fontcolor=0xAAAAAA:fontsize=h*0.03:x=w*0.03:y=h*0.03" \
    -f lavfi -i "$audio" -map 0:v -map 1:a -c:v libx264 -preset veryslow -crf 30 -pix_fmt yuv420p -tune stillimage "$@" -shortest "$OUT/$file"
}
sound_clip dolby_digital_51.mkv "Dolby Digital 5.1" "Your receiver should show Dolby Digital" 5.1 -c:a ac3 -b:a 448k
sound_clip dolby_digital_plus_51.mkv "Dolby Digital Plus 5.1" "Your receiver should show Dolby Digital Plus (DD+)" 5.1 -c:a eac3 -b:a 640k
sound_clip dolby_truehd_51.mkv "Dolby TrueHD 5.1" "Your receiver should show Dolby TrueHD" 5.1 -c:a truehd -strict -2
sound_clip dts_51.mkv "DTS 5.1" "Your receiver should show DTS" 5.1 -c:a dca -strict -2 -b:a 1509k
sound_clip pcm_71.mkv "7.1 lossless (PCM)" "Your receiver should show multichannel PCM 7.1" 7.1 -c:a flac

# Check what came out.
for f in hdr10.mp4 hlg.mp4 dolby_vision_81.mp4 dolby_vision_5.mp4; do
  echo "$f: $(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,color_transfer:stream_side_data=dv_profile -of csv=p=0 "$OUT/$f" | tr '\n' ' ')"
done
for f in dolby_digital_51.mkv dolby_digital_plus_51.mkv dolby_truehd_51.mkv dts_51.mkv pcm_71.mkv; do
  echo "$f: $(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,channels,channel_layout -of csv=p=0 "$OUT/$f")"
done
ls -la "$OUT"
