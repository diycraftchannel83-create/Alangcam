#!/usr/bin/env python3
"""Generate Android legacy and adaptive launcher icons from repository-root CamRider.png."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "CamRider.png"
RES = ROOT / "app" / "src" / "main" / "res"

if not SOURCE.is_file() or SOURCE.stat().st_size < 1024:
    raise SystemExit("ERROR: repository-root CamRider.png is missing or invalid")

artwork = Image.open(SOURCE).convert("RGBA")
if artwork.width != artwork.height:
    side = max(artwork.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.alpha_composite(artwork, ((side-artwork.width)//2, (side-artwork.height)//2))
    artwork = square

# Legacy launcher icons (Android 7.1 and older).
for density, size in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    icon = artwork.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(folder / "ic_launcher.png", optimize=True)
    icon.save(folder / "ic_launcher_round.png", optimize=True)

# Adaptive launcher foreground (Android 8+), intentionally transparent around the art.
folder = RES / "drawable-nodpi"
folder.mkdir(parents=True, exist_ok=True)
foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
fit = artwork.copy()
fit.thumbnail((344, 344), Image.Resampling.LANCZOS)
foreground.alpha_composite(fit, ((432-fit.width)//2, (432-fit.height)//2))
foreground.save(folder / "ic_launcher_art.png", optimize=True)

print(f"Generated launcher icons from {SOURCE.name} ({Image.open(SOURCE).size[0]}x{Image.open(SOURCE).size[1]}).")
