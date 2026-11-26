#!/usr/bin/env bash
# Generates the Dolby Vision clips for the in-app playback test (app/src/main/assets/testclips):
#   dv_p81_2160p.mp4  Profile 8.1: HDR10 base layer + RPU. Plays as HDR10 without Dolby Vision.
#   dv_p5_2160p.mp4   Profile 5: no compatible base layer. Without Dolby Vision processing the
#                     colours look visibly wrong (green/purple), which makes support easy to judge.
#   dv_p7_mel_2160p.mkv / dv_p7_fel_2160p.mkv
#                     Profile 7 (dual layer, one track, as disc copies are): HDR10 base layer, a 1080p
#                     enhancement layer (near-empty for MEL, a real picture for FEL) and RPU.
#   dolby_vision_7_fel_heavy.mkv (into the Companion's test clips, too big for the app)
#                     The FEL clip at disc-like bitrate, with keyframes of several MB.
# Profile 7's RPU starts from dovi_tool's own test RPUs (assets/tests/{mel,fel}_orig.bin, MIT; one
# frame each), repeated for every frame with the letterbox offsets cleared.
# The RPU is generated, not mastered: it describes a 1000-nit HDR10-like grade with no trims.
#
# Requires ffmpeg (libx265), dovi_tool (https://github.com/quietvoid/dovi_tool) and Dolby's
# mp4muxer (https://github.com/DolbyLaboratories/dlb_mp4base), on PATH or given as
# DOVI_TOOL=... MP4MUXER=... . ffmpeg 6 can't write the Dolby Vision MP4 header itself.
# Profile 7 also needs mkvmerge (MKVToolNix) and dovi_tool's sources (DOVI_TOOL_SRC=..., else cloned).
# ONLY_PROFILE7=1 makes just those.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/tv/src/main/assets/testclips"
COMPANION_CLIPS="$ROOT/companion/Nitrate.Companion/TestClips"
DOVI_TOOL="${DOVI_TOOL:-$(command -v dovi_tool)}"
MP4MUXER="${MP4MUXER:-$(command -v mp4muxer || true)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FF=(ffmpeg -hide_banner -loglevel error -y)
FRAMES=48   # 2 s at 24 fps
SRC="testsrc2=size=3840x2160:rate=24:duration=2"
HDR10="log-level=error:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"

profile7() {
  local src="${DOVI_TOOL_SRC:-}"
  if [[ -z $src ]]; then src="$WORK/dovi_tool"; git clone -q --depth 1 https://github.com/quietvoid/dovi_tool.git "$src"; fi
  cat > "$WORK/p7-edit.json" <<JSON
{ "duplicate": [ { "source": 0, "offset": 1, "length": $((FRAMES - 1)) } ], "active_area": { "crop": true } }
JSON
  for k in mel fel; do "$DOVI_TOOL" editor -i "$src/assets/tests/${k}_orig.bin" -j "$WORK/p7-edit.json" -o "$WORK/p7-$k.bin" >/dev/null; done
  # Base layer as in the other clips; enhancement layers at 1080p: flat for MEL, a real picture for FEL.
  "${FF[@]}" -f lavfi -i "$SRC" -c:v libx265 -pix_fmt yuv420p10le -crf 42 -x265-params "$HDR10" -f hevc "$WORK/p7-bl.hevc"
  "${FF[@]}" -f lavfi -i "color=c=0x808080:size=1920x1080:rate=24:duration=2" -c:v libx265 -pix_fmt yuv420p10le -crf 51 -x265-params "log-level=error:repeat-headers=1" -f hevc "$WORK/p7-el-mel.hevc"
  "${FF[@]}" -f lavfi -i "testsrc2=size=1920x1080:rate=24:duration=2" -vf "noise=alls=12:allf=t" -c:v libx265 -pix_fmt yuv420p10le -crf 36 -x265-params "log-level=error:repeat-headers=1" -f hevc "$WORK/p7-el-fel.hevc"
  # Disc-like: dense picture, 40 Mbps, keyframes as big as x265 makes them at this level.
  "${FF[@]}" -f lavfi -i "$SRC" -vf "noise=alls=70:allf=t+u" -c:v libx265 -pix_fmt yuv420p10le -b:v 40M \
    -x265-params "$HDR10:keyint=24:min-keyint=24:vbv-maxrate=240000:vbv-bufsize=240000:zones=0,0,q=2/24,24,q=2" -f hevc "$WORK/p7-bl-heavy.hevc"
  "${FF[@]}" -f lavfi -i "testsrc2=size=1920x1080:rate=24:duration=2" -vf "noise=alls=70:allf=t+u" -c:v libx265 -pix_fmt yuv420p10le -b:v 12M \
    -x265-params "log-level=error:repeat-headers=1:keyint=24:min-keyint=24:vbv-maxrate=120000:vbv-bufsize=120000:zones=0,0,q=4/24,24,q=4" -f hevc "$WORK/p7-el-heavy.hevc"
  make() { # name, base layer, enhancement layer, rpu, out
    "$DOVI_TOOL" inject-rpu -i "$3" --rpu-in "$4" -o "$WORK/$1-el.hevc" >/dev/null
    "$DOVI_TOOL" mux --bl "$2" --el "$WORK/$1-el.hevc" -o "$WORK/$1.hevc" >/dev/null
    mkvmerge -q -o "$5" --default-duration 0:24p "$WORK/$1.hevc"
    local conf; conf=$(ffprobe -v error -select_streams v:0 -show_entries stream_side_data=dv_profile,el_present_flag -of csv=p=0 "$5")
    echo "$(basename "$5"): config [$conf], $(stat -c %s "$5") bytes"
    [[ $conf == 7,* || $conf == *7* ]] || { echo "  FAILED: expected profile 7" >&2; exit 1; }
  }
  make mel "$WORK/p7-bl.hevc" "$WORK/p7-el-mel.hevc" "$WORK/p7-mel.bin" "$OUT/dv_p7_mel_2160p.mkv"
  make fel "$WORK/p7-bl.hevc" "$WORK/p7-el-fel.hevc" "$WORK/p7-fel.bin" "$OUT/dv_p7_fel_2160p.mkv"
  make heavy "$WORK/p7-bl-heavy.hevc" "$WORK/p7-el-heavy.hevc" "$WORK/p7-fel.bin" "$COMPANION_CLIPS/dolby_vision_7_fel_heavy.mkv"
}
if [[ ${ONLY_PROFILE7:-} == 1 ]]; then profile7; exit 0; fi

# Base layers. 8.1 is a normal HDR10 encode; 5 is the same picture flagged with unspecified
# colour (as Profile 5 streams are), since its true colour space only Dolby Vision can interpret.
"${FF[@]}" -f lavfi -i "$SRC" -c:v libx265 -pix_fmt yuv420p10le -crf 42 \
  -x265-params "log-level=error:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
  -f hevc "$WORK/base81.hevc"
"${FF[@]}" -f lavfi -i "$SRC" -c:v libx265 -pix_fmt yuv420p10le -crf 42 \
  -x265-params "log-level=error:repeat-headers=1:colorprim=2:transfer=2:colormatrix=2" \
  -f hevc "$WORK/base5.hevc"

for profile in 8.1 5; do
  cat > "$WORK/rpu-$profile.json" <<JSON
{
  "cm_version": "V40",
  "profile": "$profile",
  "length": $FRAMES,
  "level6": {
    "max_display_mastering_luminance": 1000,
    "min_display_mastering_luminance": 1,
    "max_content_light_level": 1000,
    "max_frame_average_light_level": 400
  }
}
JSON
  "$DOVI_TOOL" generate -j "$WORK/rpu-$profile.json" -o "$WORK/rpu-$profile.bin"
done

"$DOVI_TOOL" inject-rpu -i "$WORK/base81.hevc" --rpu-in "$WORK/rpu-8.1.bin" -o "$WORK/dv81.hevc"
"$DOVI_TOOL" inject-rpu -i "$WORK/base5.hevc" --rpu-in "$WORK/rpu-5.bin" -o "$WORK/dv5.hevc"

"$MP4MUXER" -i "$WORK/dv81.hevc" -o "$OUT/dv_p81_2160p.mp4" --dv-profile 8 --dv-bl-compatible-id 1 \
  --mpeg4-comp-brand mp42,iso6,isom,msdh,dby1 --overwrite >/dev/null
"$MP4MUXER" -i "$WORK/dv5.hevc" -o "$OUT/dv_p5_2160p.mp4" --dv-profile 5 \
  --mpeg4-comp-brand mp42,iso6,isom,msdh,dby1 --overwrite >/dev/null

# Verify: the container must carry the right Dolby Vision configuration, and every frame an RPU.
for f in dv_p81_2160p.mp4:8:1 dv_p5_2160p.mp4:5:0; do
  IFS=: read -r file profile compat <<<"$f"
  conf=$(ffprobe -v error -select_streams v:0 -show_entries stream_side_data=dv_profile,dv_bl_signal_compatibility_id,rpu_present_flag,bl_present_flag -of csv=p=0 "$OUT/$file")
  rpus=$(ffprobe -v error -select_streams v:0 -show_frames -show_entries frame_side_data=side_data_type -of csv=p=0 "$OUT/$file" | grep -c "Dolby Vision" || true)
  echo "$file: config [$conf], frames with Dolby Vision metadata: $rpus/$FRAMES"
  [[ $conf == *"$profile"* && $rpus -eq $FRAMES ]] || { echo "  FAILED: expected profile $profile with an RPU on every frame" >&2; exit 1; }
done
ls -la "$OUT"/dv_*
profile7
