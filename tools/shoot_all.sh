#!/usr/bin/env bash
# All design screenshots (design stateModel.qaCombos) in one go, after the app reached S2 on the emulator:
#   tools/shoot_all.sh [first-shot-name]   -> ../spatial-app-factory/apps/bianzhong/build/shots/*.png
# The Stage origin is the head at launch: place=auto stands the rack 2.5 m ahead, facing the camera.
set -u
cd "$(dirname "$0")/.."
Q="bash tools/qa_shot.sh"
from="${1:-}"
run() { local n="$1"; shift; if [ -n "$from" ] && [ "$n" != "$from" ]; then return; fi; from=""; $Q "$n" "$@"; }
run S0 'lang=zh hover=off' 'state=S0 freeze=1'
run S1 'freeze=0 state=S1'
run S2 'state=S2'
run S2-near 'state=S2 near=1'
run S2a 'near=0 place=wall'
run S17 'state=S17'
run S3 'state=S2' 'place=auto'
run S3-coach 'freeze=1 coach=1'
run S4-B6-zheng 'freeze=0 hover=6,zheng'
run S4-B6-ce 'hover=6,ce'
run S5-B6-zheng 'hover=off freeze=1 strike=6,zheng'
run S5-B1-ce 'strike=1,ce'
run S6 'freeze=0 mode=pick'
run S7-molihua-12 'freeze=1 mode=demo,song=molihua,step=12'
run S8-molihua-5 'freeze=1 mode=follow,song=molihua,step=5'
run S8-miss 'strike=1,zheng'
run S8-huanlesong 'freeze=1 mode=follow,song=huanlesong,step=1'
run S9 'freeze=1 mode=follow,song=molihua,done=1'
run S10 'freeze=0 mode=free menu=1'
run S12 'menu=0 scale=2' 'place=auto'
run S13 'scale=1 state=S13'
run S14 'state=S14'
run S15 'retry=1' 'labels=0 place=auto'
run S15-hover 'hover=6,zheng'
run S16 'hover=off labels=1 lang=en' 'place=auto'
