#!/usr/bin/env bash
# 重新生成 imux 宣传片：渲染画面 → 截取配乐 → 合成 out/imux-promo.mp4
# 依赖：huashu-motion skill（om）、ffmpeg、本机 Chrome
set -euo pipefail
cd "$(dirname "$0")"
OM="${OM:-$HOME/.claude/skills/huashu-motion/bin/om}"
SONG=ref/song-full.mp3   # 完整歌曲，成片 0 秒 = 歌曲 0 秒（见 treatment.md）

"$OM" check .
"$OM" render . --silent "$@"
VIDEO=$(ls -t out/*-v*.mp4 | head -1)
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$VIDEO")
ffmpeg -y -v error -t "$DUR" -i "$SONG" \
  -af "afade=t=out:st=$(echo "$DUR - 1.4" | bc):d=1.4,apad,atrim=0:$DUR,loudnorm=I=-14:TP=-1.5:LRA=11,alimiter=limit=0.7:level=false" \
  -ar 48000 out/bgm-cut.m4a
ffmpeg -y -v error -i "$VIDEO" -i out/bgm-cut.m4a -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest out/imux-promo.mp4
echo "done: out/imux-promo.mp4 (video: ${VIDEO})"
