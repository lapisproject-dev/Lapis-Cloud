#!/usr/bin/env python3
"""Prepare the image ZIP for the Lapis Cloud article import.

Reads a flat directory of images (JPEG, PNG, WebP, SVG), converts and shrinks them so the server accepts them, and writes ONE flat ZIP
whose entries are named ``<slug>.jpg`` or ``<slug>.png`` -- exactly what ``/articles/import`` (field "Bilder (ZIP, optional)") expects.

Usage:
    prepare-article-images.py INPUT_DIR -o OUT.zip [--mapping map.csv] [--quiet]

Exit codes: 0 = everything went in, 1 = a ZIP was written but some images were skipped or changed with a warning,
2 = fatal (nothing written). See README-article-images.adoc.

Needs Python 3.9+ and Pillow. SVG input additionally needs ``rsvg-convert`` or ImageMagick (``magick``) on the PATH.
"""
from __future__ import annotations

import argparse
import csv
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from typing import Dict, List, Optional, Tuple

try:
    from PIL import Image, ImageOps
except ImportError:  # pragma: no cover - exercised only on a machine without Pillow
    Image = None  # type: ignore[assignment]
    ImageOps = None  # type: ignore[assignment]

# --- Limits. Keep in sync with the server: ArticleImportZipPolicy / ArticleImportPolicy.SLUG_PATTERN / CoverImageLimits.IMPORTED_ARTICLE_COVER. ---
SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:[-_][a-z0-9]+)*$")  # keep in sync with ArticleImportPolicy.SLUG_PATTERN
SLUG_MAX_LENGTH = 120
MIN_WIDTH = 300
MIN_HEIGHT = 150
MIN_ASPECT = 0.5  # width / height
MAX_ASPECT = 4.0
MAX_LONG_EDGE = 1600
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_IMAGES = 100
MAX_ZIP_BYTES = 25 * 1024 * 1024
JPEG_QUALITIES = (88, 82, 76, 70)
MAX_SOURCE_PIXELS = 40_000_000
SVG_TIMEOUT_SECONDS = 60

RASTER_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
SUPPORTED_EXTENSIONS = RASTER_EXTENSIONS | {".svg"}

EXIT_OK = 0
EXIT_WARNINGS = 1
EXIT_FATAL = 2


class Report:
    """Collects the lines of the run; ``warnings`` decides the exit code."""

    def __init__(self, quiet: bool) -> None:
        self.quiet = quiet
        self.warnings = 0
        self.lines: List[str] = []

    def info(self, text: str) -> None:
        self.lines.append(text)
        if not self.quiet:
            print(text)

    def warn(self, text: str) -> None:
        self.warnings += 1
        self.lines.append("WARNING: " + text)
        print("WARNING: " + text, file=sys.stderr)

    def error(self, text: str) -> None:
        self.lines.append("ERROR: " + text)
        print("ERROR: " + text, file=sys.stderr)


def read_mapping(path: str, report: Report) -> Dict[str, str]:
    """CSV ``filename,slug`` (an optional header row and ``#`` comment lines are ignored)."""
    mapping: Dict[str, str] = {}
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.reader(handle):
            if not row or not row[0].strip() or row[0].lstrip().startswith("#"):
                continue
            if len(row) < 2:
                report.warn("mapping line ignored (expected filename,slug): %r" % (row,))
                continue
            name, slug = row[0].strip(), row[1].strip()
            if name.lower() == "filename" and slug.lower() == "slug":
                continue
            mapping[name] = slug
    return mapping


def valid_slug(slug: str) -> bool:
    return len(slug) <= SLUG_MAX_LENGTH and SLUG_PATTERN.match(slug) is not None


def list_sources(input_dir: str, report: Report) -> List[str]:
    """Regular files of the directory (not recursive). Symlinks are never followed, hidden files and unknown types are skipped."""
    found: List[str] = []
    for name in sorted(os.listdir(input_dir)):
        full = os.path.join(input_dir, name)
        if name.startswith("."):
            continue
        if os.path.islink(full):
            report.warn("%s: symbolic link skipped" % name)
            continue
        if not os.path.isfile(full):
            continue
        if os.path.splitext(name)[1].lower() not in SUPPORTED_EXTENSIONS:
            report.warn("%s: unsupported file type skipped (JPEG, PNG, WebP and SVG are supported)" % name)
            continue
        found.append(name)
    return found


def render_svg(source: str, target_png: str) -> Optional[str]:
    """Renders an SVG to a PNG with rsvg-convert, else ImageMagick. Returns ``None`` on success, else the reason. Never uses a shell."""
    source = os.path.abspath(source)
    target_png = os.path.abspath(target_png)
    rsvg = shutil.which("rsvg-convert")
    magick = shutil.which("magick")
    if rsvg:
        command = [rsvg, "-w", str(MAX_LONG_EDGE), "-f", "png", "-o", target_png, source]
    elif magick:
        command = [magick, "-background", "none", "-density", "192", source, "-resize", "%dx" % MAX_LONG_EDGE, target_png]
    else:
        return "no SVG renderer"
    try:
        done = subprocess.run(command, timeout=SVG_TIMEOUT_SECONDS, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    except subprocess.TimeoutExpired:
        return "SVG rendering timed out"
    except OSError as error:
        return "SVG renderer could not be started: %s" % error
    if done.returncode != 0 or not os.path.isfile(target_png):
        return "SVG rendering failed"
    return None


def flatten_to_rgb(image: "Image.Image") -> "Image.Image":
    """RGBA/LA/P with transparency are composited onto white before a JPEG is written."""
    if image.mode in ("RGBA", "LA") or (image.mode == "P" and "transparency" in image.info):
        rgba = image.convert("RGBA")
        background = Image.new("RGB", rgba.size, (255, 255, 255))
        background.paste(rgba, mask=rgba.getchannel("A"))
        return background
    return image.convert("RGB")


def encode_jpeg(image: "Image.Image") -> Optional[bytes]:
    rgb = flatten_to_rgb(image)
    for quality in JPEG_QUALITIES:
        buffer = io.BytesIO()
        rgb.save(buffer, format="JPEG", quality=quality, optimize=True)  # no EXIF: none is passed
        if buffer.tell() <= MAX_IMAGE_BYTES:
            return buffer.getvalue()
    return None


def encode_png(image: "Image.Image") -> Optional[bytes]:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue() if buffer.tell() <= MAX_IMAGE_BYTES else None


def check_dimensions(width: int, height: int) -> Optional[str]:
    if width < MIN_WIDTH or height < MIN_HEIGHT:
        return "too small (%dx%d, needs at least %d px wide and %d px high)" % (width, height, MIN_WIDTH, MIN_HEIGHT)
    aspect = width / float(height)
    if aspect < MIN_ASPECT or aspect > MAX_ASPECT:
        return "unusual proportions (%dx%d, width/height must be between 1:2 and 4:1)" % (width, height)
    return None


def convert_one(name: str, source_path: str, svg_png: Optional[str], report: Report) -> Optional[Tuple[str, bytes, Tuple[int, int]]]:
    """Returns ``(extension, bytes, (w, h))`` of the prepared image, or ``None`` (a warning was reported)."""
    extension = os.path.splitext(name)[1].lower()
    path = svg_png if extension == ".svg" else source_path
    assert path is not None
    try:
        with Image.open(path) as opened:
            animated = getattr(opened, "n_frames", 1) > 1
            opened.load()
            image = ImageOps.exif_transpose(opened)  # the orientation is baked in BEFORE any measuring
    except Exception as error:  # Pillow raises many types for broken or oversized files
        report.warn("%s: cannot be read (%s)" % (name, type(error).__name__))
        return None
    if animated:
        report.warn("%s: animated, only the first frame is used" % name)
    problem = check_dimensions(*image.size)
    if problem:
        report.warn("%s: skipped, %s" % (name, problem))
        return None
    image.thumbnail((MAX_LONG_EDGE, MAX_LONG_EDGE), Image.LANCZOS)  # thumbnail never enlarges
    if extension in (".png", ".svg"):
        data = encode_png(image)
        out_extension = ".png"
    else:
        data = encode_jpeg(image)
        out_extension = ".jpg"
    if data is None:
        report.warn("%s: skipped, larger than 5 MB even after optimizing" % name)
        return None
    return out_extension, data, image.size


def run(input_dir: str, output: str, mapping_path: Optional[str], quiet: bool) -> int:
    report = Report(quiet)
    if Image is None:
        report.error("Pillow is not installed (pip install pillow)")
        return EXIT_FATAL
    Image.MAX_IMAGE_PIXELS = MAX_SOURCE_PIXELS
    if not os.path.isdir(input_dir):
        report.error("not a directory: %s" % input_dir)
        return EXIT_FATAL
    mapping: Dict[str, str] = {}
    if mapping_path:
        try:
            mapping = read_mapping(mapping_path, report)
        except OSError as error:
            report.error("cannot read the mapping file: %s" % error)
            return EXIT_FATAL

    sources = list_sources(input_dir, report)
    for name in mapping:
        if name not in sources:
            report.warn("mapping entry without a matching file: %s" % name)

    # Decide every slug BEFORE any work: collisions and bad slugs are fatal, nothing half-done.
    slug_of: Dict[str, str] = {}
    owners: Dict[str, List[str]] = {}
    fatal = False
    for name in sources:
        slug = mapping.get(name, os.path.splitext(name)[0].lower())
        if not valid_slug(slug):
            report.error(
                "%s: %r is not a valid slug (lowercase letters, digits, '-' and '_' between them, at most %d characters); "
                "rename the file or list it in --mapping" % (name, slug, SLUG_MAX_LENGTH)
            )
            fatal = True
            continue
        slug_of[name] = slug
        owners.setdefault(slug, []).append(name)
    for slug, names in owners.items():
        if len(names) > 1:
            report.error("several files would become the slug %r: %s -- resolve it with --mapping" % (slug, ", ".join(names)))
            fatal = True
    if fatal:
        return EXIT_FATAL

    svg_names = [n for n in sources if n.lower().endswith(".svg")]
    svg_unavailable = bool(svg_names) and not (shutil.which("rsvg-convert") or shutil.which("magick"))
    if svg_unavailable:
        report.error("SVG files found but neither rsvg-convert nor ImageMagick (magick) is installed: %s" % ", ".join(svg_names))

    entries: List[Tuple[str, bytes]] = []
    with tempfile.TemporaryDirectory() as scratch:
        for name in sources:
            source_path = os.path.join(input_dir, name)
            svg_png: Optional[str] = None
            if name.lower().endswith(".svg"):
                if svg_unavailable:
                    continue
                svg_png = os.path.join(scratch, "%d.png" % len(entries))
                failure = render_svg(source_path, svg_png)
                if failure:
                    report.warn("%s: skipped, %s" % (name, failure))
                    continue
            prepared = convert_one(name, source_path, svg_png, report)
            if prepared is None:
                continue
            out_extension, data, size = prepared
            entry_name = slug_of[name] + out_extension
            entries.append((entry_name, data))
            report.info("ok  %-40s %4dx%-4d %6d KB  (from %s)" % (entry_name, size[0], size[1], (len(data) + 1023) // 1024, name))

    if not entries:
        report.error("no image could be prepared, no ZIP written")
        return EXIT_FATAL
    if len(entries) > MAX_IMAGES:
        report.error("%d images, but one ZIP may hold at most %d: split the input directory" % (len(entries), MAX_IMAGES))
        return EXIT_FATAL

    with zipfile.ZipFile(output, "w") as archive:
        for entry_name, data in entries:
            info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED  # images are compressed already; stored entries can never trip the server's ratio guard
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)
    if os.path.getsize(output) > MAX_ZIP_BYTES:
        os.remove(output)
        report.error("the ZIP would be larger than 25 MiB: split the input directory into several ZIPs")
        return EXIT_FATAL

    report.info("")
    report.info("%d image(s) written to %s (%d KB)" % (len(entries), output, (os.path.getsize(output) + 1023) // 1024))
    if svg_unavailable:
        return EXIT_FATAL
    return EXIT_WARNINGS if report.warnings else EXIT_OK


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare the image ZIP for the Lapis Cloud article import.")
    parser.add_argument("input_dir", help="flat directory with the source images")
    parser.add_argument("-o", "--output", required=True, help="the ZIP to write")
    parser.add_argument("--mapping", help="CSV file 'filename,slug' for files whose name is not the slug")
    parser.add_argument("--quiet", action="store_true", help="only warnings and errors")
    args = parser.parse_args(argv)
    return run(args.input_dir, args.output, args.mapping, args.quiet)


if __name__ == "__main__":
    sys.exit(main())
