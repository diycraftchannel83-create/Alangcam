#!/usr/bin/env python3
"""Generate all Android launcher icon resources from the supplied CamRider PRO artwork."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app" / "src" / "main" / "assets" / "CamRider PRO_ Racing Lens Icon.png"
RES = ROOT / "app" / "src" / "main" / "res"

if not SOURCE.is_file():
    raise SystemExit(f"Launcher artwork not found: {SOURCE}")

image = Image.open(SOURCE).convert("RGBA")
if image.width != image.height:
    side = max(image.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 255))
    canvas.alpha_composite(image, ((side-image.width)//2, (side-image.height)//2))
    image = canvas

# Legacy launcher assets for Android versions before adaptive icons.
for density, size in {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}.items():
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    icon = image.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(folder / "ic_launcher.png", optimize=True)
    icon.save(folder / "ic_launcher_round.png", optimize=True)

# Adaptive foreground: add breathing room so the circular/squircle mask
# does not crop the motorcycle, lens, speed scale, or CamRider PRO lettering.
size = 432
foreground = Image.new("RGBA", (size, size), (0, 0, 0, 0))
art = image.resize((344, 344), Image.Resampling.LANCZOS)
foreground.alpha_composite(art, ((size-344)//2, (size-344)//2))
out = RES / "drawable-nodpi"
out.mkdir(parents=True, exist_ok=True)
foreground.save(out / "ic_launcher_art.png", optimize=True)
print("Generated CamRider PRO launcher icons for all Android densities.")
