#!/usr/bin/env python3
"""生成 Link Mesh 应用图标与通知图标。

背景：res/mipmap-*/ 和 res/drawable-*/ 下的 PNG 曾被外部清理误删，
导致 AAPT 报 "resource mipmap/ic_launcher not found"。构建前先跑这个脚本。

用法：python3 scripts/gen_icons.py
产物：
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png
  app/src/main/res/drawable-{mdpi..xxxhdpi}/ic_stat_notify.png
  /var/minis/shared/lanmesh/icon-512.png（备份供 README/商店用）
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
BG = (46, 168, 132, 255)   # 亮淡绿
SCALE = 0.76               # 图形相对中心缩放


def draw_icon(bg=True):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    fg = (255, 255, 255, 255)
    if bg:
        dr.rounded_rectangle([0, 0, S, S], radius=int(S * 0.225), fill=BG)
    cx = cy = S / 2
    raw = [(S * 0.5, S * 0.245), (S * 0.25, S * 0.715), (S * 0.75, S * 0.715)]
    pts = [(cx + (x - cx) * SCALE, cy + (y - cy) * SCALE) for x, y in raw]
    w = int(S * 0.055 * SCALE)
    for i in range(3):
        dr.line([pts[i], pts[(i + 1) % 3]], fill=fg, width=w)
    r = int(S * 0.098 * SCALE)
    for p in pts:
        dr.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=fg)
    return im


def main():
    icon = draw_icon()
    for d, s in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        out = os.path.join(RES, "mipmap-" + d)
        os.makedirs(out, exist_ok=True)
        icon.resize((s, s), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    mono = draw_icon(bg=False)
    for d, s in {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}.items():
        out = os.path.join(RES, "drawable-" + d)
        os.makedirs(out, exist_ok=True)
        mono.resize((s, s), Image.LANCZOS).save(os.path.join(out, "ic_stat_notify.png"))
    backup = "/var/minis/shared/lanmesh"
    if os.path.isdir(backup):
        icon.resize((512, 512), Image.LANCZOS).save(os.path.join(backup, "icon-512.png"))
    print("icons generated")


if __name__ == "__main__":
    main()
