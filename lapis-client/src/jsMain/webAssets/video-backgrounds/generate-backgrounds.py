#!/usr/bin/env python3
"""Generates the six built-in video-conference background images (V1.4.23).

Own work, licensed like the rest of the repository. Produces six 1280x720 surfaces with a soft
radial depth gradient, desaturated on purpose so the participant's face stays the only saturated
element in the frame. No figurative motifs (no fake bookshelves or windows).

Usage: python3 generate-backgrounds.py   (writes the six .webp files next to this script)
Requires: Pillow with WebP support.
"""
import math
import os

from PIL import Image, ImageFilter

WIDTH, HEIGHT = 1280, 720

PALETTES = {
    "bg-warm-grey": ("#D6D2CB", "#96918A"),
    "bg-cool-blue": ("#CED8E4", "#6C7F96"),
    "bg-sage": ("#CAD6C9", "#7E917C"),
    "bg-sandstone": ("#E0D3C0", "#A38F76"),
    "bg-midnight": ("#3A4557", "#161C26"),
    "bg-studio": ("#F1F1F0", "#BFBFBE"),
}


def hex_to_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))


def radial_gradient(light, dark):
    """Radial gradient, centre slightly above the middle, light in the centre, dark at the corners."""
    light_rgb, dark_rgb = hex_to_rgb(light), hex_to_rgb(dark)
    cx, cy = WIDTH / 2, HEIGHT * 0.42
    max_dist = math.hypot(max(cx, WIDTH - cx), max(cy, HEIGHT - cy))
    image = Image.new("RGB", (WIDTH, HEIGHT))
    pixels = image.load()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            t = min(1.0, math.hypot(x - cx, y - cy) / max_dist)
            t = t * t * (3 - 2 * t)  # smoothstep
            pixels[x, y] = tuple(round(light_rgb[i] + (dark_rgb[i] - light_rgb[i]) * t) for i in range(3))
    return image


def main():
    out_dir = os.path.dirname(os.path.abspath(__file__))
    for name, (light, dark) in PALETTES.items():
        image = radial_gradient(light, dark).filter(ImageFilter.GaussianBlur(2))
        path = os.path.join(out_dir, f"{name}.webp")
        image.save(path, "WEBP", quality=82, method=6)
        print(f"{name}: {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
