#!/usr/bin/env python3
"""Verifies the channel test clips: in time slot N only channel N may carry sound.

Each clip is decoded and, per 3-second slot, the level of every channel is measured. A clip
fails if the slot's own channel is quiet (< -40 dB) or any other channel is within 30 dB of it.
Exits non-zero on failure so tools/generate-channel-test.sh stops before shipping a bad clip.
"""
import subprocess, sys, struct, math
failed = False
SEG = 3.0
for path in sys.argv[1:]:
    ch = int(subprocess.check_output(["ffprobe","-v","error","-show_entries","stream=channels","-of","csv=p=0",path]).decode().split()[0])
    raw = subprocess.check_output(["ffmpeg","-v","error","-i",path,"-f","f32le","-ac",str(ch),"-ar","48000","-"])
    n = len(raw)//4//ch
    data = struct.unpack(f"<{n*ch}f", raw[:n*ch*4])
    seg = int(SEG*48000)
    worst_leak = -999.0; ok = True
    rows = []
    for s in range(ch):
        db = []
        for c in range(ch):
            acc = 0.0; cnt = 0
            for i in range(s*seg, min((s+1)*seg, n), 4):
                v = data[i*ch+c]; acc += v*v; cnt += 1
            rms = math.sqrt(acc/max(cnt,1)); db.append(20*math.log10(rms+1e-9))
        own = db[s]; leak = max(d for c,d in enumerate(db) if c != s)
        worst_leak = max(worst_leak, leak - own)
        if own < -40 or leak - own > -30: ok = False
        rows.append(f"slot{s}: own {own:6.1f} dB, loudest other {leak:6.1f} dB")
    print(f"{path:18} ch={ch} {'OK ' if ok else 'BAD'} worst other-vs-own {worst_leak:6.1f} dB")
    failed |= not ok
    if not ok: print("   " + "\n   ".join(rows))
sys.exit(1 if failed else 0)
