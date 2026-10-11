"""Tests for prepare-article-images.py. Run with:  python3 -I -m unittest scripts/test_prepare_article_images.py

Every fixture is generated at run time in a temporary directory (Pillow draws the images); no image is stored in the repository.
"""
from __future__ import annotations

import importlib.util
import io
import os
import shutil
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

from PIL import Image

_HERE = os.path.dirname(os.path.abspath(__file__))
_SPEC = importlib.util.spec_from_file_location("prepare_article_images", os.path.join(_HERE, "prepare-article-images.py"))
tool = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(tool)

SVG_TEXT = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="300" viewBox="0 0 600 300">'
    '<rect width="600" height="300" fill="#1d4ed8"/><circle cx="300" cy="150" r="90" fill="#fde047"/></svg>'
)


def draw(path: str, size, mode="RGB", fmt=None, color=(30, 120, 200)) -> None:
    image = Image.new(mode, size, color if mode == "RGB" else color + (128,))
    image.save(path, format=fmt)


class PrepareArticleImagesTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.input = os.path.join(self._tmp.name, "in")
        os.mkdir(self.input)
        self.out = os.path.join(self._tmp.name, "out.zip")

    def run_tool(self, *extra: str):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = tool.main([self.input, "-o", self.out, *extra])
        return code, stdout.getvalue(), stderr.getvalue()

    def names(self):
        with zipfile.ZipFile(self.out) as archive:
            return sorted(archive.namelist())

    def image_in_zip(self, name: str) -> Image.Image:
        with zipfile.ZipFile(self.out) as archive:
            return Image.open(io.BytesIO(archive.read(name)))

    def test_jpeg_and_png_keep_their_format_and_become_a_flat_zip(self):
        draw(os.path.join(self.input, "mein-artikel.jpg"), (800, 600), fmt="JPEG")
        draw(os.path.join(self.input, "zweiter_artikel.png"), (640, 480), fmt="PNG")
        code, _, _ = self.run_tool()
        self.assertEqual(0, code)
        self.assertEqual(["mein-artikel.jpg", "zweiter_artikel.png"], self.names())
        with zipfile.ZipFile(self.out) as archive:
            for info in archive.infolist():
                self.assertNotIn("/", info.filename)
                self.assertFalse(info.is_dir())
        self.assertEqual("JPEG", self.image_in_zip("mein-artikel.jpg").format)
        self.assertEqual("PNG", self.image_in_zip("zweiter_artikel.png").format)

    def test_webp_becomes_a_jpeg_and_transparency_is_flattened_to_white(self):
        draw(os.path.join(self.input, "bild.webp"), (500, 300), mode="RGBA", fmt="WEBP", color=(0, 0, 0))
        code, _, _ = self.run_tool()
        self.assertEqual(0, code)
        self.assertEqual(["bild.jpg"], self.names())
        converted = self.image_in_zip("bild.jpg")
        self.assertEqual("JPEG", converted.format)
        pixel = converted.convert("RGB").getpixel((10, 10))
        self.assertTrue(all(channel > 100 for channel in pixel), "half transparent black on white must be a grey, not black: %r" % (pixel,))

    def test_large_images_are_reduced_to_1600_px_but_small_ones_are_never_enlarged(self):
        draw(os.path.join(self.input, "gross.jpg"), (4000, 2000), fmt="JPEG")
        draw(os.path.join(self.input, "klein-aber-ok.png"), (320, 200), fmt="PNG")
        code, _, _ = self.run_tool()
        self.assertEqual(0, code)
        self.assertEqual((1600, 800), self.image_in_zip("gross.jpg").size)
        self.assertEqual((320, 200), self.image_in_zip("klein-aber-ok.png").size)

    def test_exif_orientation_is_baked_in_before_measuring(self):
        path = os.path.join(self.input, "gedreht.jpg")
        image = Image.new("RGB", (200, 400), (10, 20, 30))
        exif = Image.Exif()
        exif[0x0112] = 6  # rotate 90 degrees: visible shape is 400 x 200
        image.save(path, format="JPEG", exif=exif)
        code, _, _ = self.run_tool()
        self.assertEqual(0, code)
        self.assertEqual((400, 200), self.image_in_zip("gedreht.jpg").size)

    def test_too_small_and_extreme_proportions_are_skipped_and_reported(self):
        draw(os.path.join(self.input, "gut.jpg"), (600, 400), fmt="JPEG")
        draw(os.path.join(self.input, "zu-schmal.jpg"), (299, 300), fmt="JPEG")
        draw(os.path.join(self.input, "zu-flach.png"), (900, 149), fmt="PNG")
        draw(os.path.join(self.input, "zu-breit.png"), (1300, 300), fmt="PNG")
        code, _, stderr = self.run_tool()
        self.assertEqual(1, code, "a ZIP was written but images were skipped")
        self.assertEqual(["gut.jpg"], self.names())
        self.assertIn("zu-schmal.jpg: skipped", stderr)
        self.assertIn("zu-flach.png: skipped", stderr)
        self.assertIn("zu-breit.png: skipped", stderr)

    def test_the_boundaries_themselves_are_accepted(self):
        draw(os.path.join(self.input, "grenze-breit.png"), (1200, 300), fmt="PNG")  # 4:1
        draw(os.path.join(self.input, "grenze-hoch.png"), (300, 600), fmt="PNG")  # 1:2
        draw(os.path.join(self.input, "grenze-klein.png"), (300, 150), fmt="PNG")
        code, _, _ = self.run_tool()
        self.assertEqual(0, code)
        self.assertEqual(3, len(self.names()))

    def test_two_sources_for_one_slug_are_fatal_and_name_the_mapping_option(self):
        draw(os.path.join(self.input, "artikel.jpg"), (600, 400), fmt="JPEG")
        draw(os.path.join(self.input, "artikel.png"), (600, 400), fmt="PNG")
        code, _, stderr = self.run_tool()
        self.assertEqual(2, code)
        self.assertIn("--mapping", stderr)
        self.assertFalse(os.path.exists(self.out), "nothing is written")

    def test_a_file_name_that_is_no_slug_is_fatal_unless_the_mapping_names_it(self):
        draw(os.path.join(self.input, "Mein Bild (1).jpg"), (600, 400), fmt="JPEG")
        code, _, stderr = self.run_tool()
        self.assertEqual(2, code)
        self.assertIn("not a valid slug", stderr)
        mapping = os.path.join(self._tmp.name, "map.csv")
        with open(mapping, "w", encoding="utf-8") as handle:
            handle.write("filename,slug\nMein Bild (1).jpg,mein-bild\n")
        code, _, _ = self.run_tool("--mapping", mapping)
        self.assertEqual(0, code)
        self.assertEqual(["mein-bild.jpg"], self.names())

    def test_symlinks_hidden_files_and_unknown_types_are_not_followed_or_read(self):
        draw(os.path.join(self.input, "echt.jpg"), (600, 400), fmt="JPEG")
        target = os.path.join(self._tmp.name, "outside.jpg")
        draw(target, (600, 400), fmt="JPEG")
        os.symlink(target, os.path.join(self.input, "verweis.jpg"))
        with open(os.path.join(self.input, ".DS_Store"), "wb") as handle:
            handle.write(b"x")
        with open(os.path.join(self.input, "notiz.txt"), "w") as handle:
            handle.write("x")
        code, _, stderr = self.run_tool()
        self.assertEqual(1, code)
        self.assertEqual(["echt.jpg"], self.names())
        self.assertIn("symbolic link skipped", stderr)

    def test_a_corrupt_image_is_skipped_with_a_warning_not_a_crash(self):
        draw(os.path.join(self.input, "gut.jpg"), (600, 400), fmt="JPEG")
        with open(os.path.join(self.input, "kaputt.jpg"), "wb") as handle:
            handle.write(b"this is not a jpeg")
        code, _, stderr = self.run_tool()
        self.assertEqual(1, code)
        self.assertEqual(["gut.jpg"], self.names())
        self.assertIn("kaputt.jpg: cannot be read", stderr)

    def test_a_directory_without_usable_images_is_fatal_and_writes_nothing(self):
        draw(os.path.join(self.input, "winzig.jpg"), (50, 50), fmt="JPEG")
        code, _, _ = self.run_tool()
        self.assertEqual(2, code)
        self.assertFalse(os.path.exists(self.out))

    @unittest.skipUnless(shutil.which("rsvg-convert") or shutil.which("magick"), "no SVG renderer installed")
    def test_svg_becomes_a_png(self):
        with open(os.path.join(self.input, "logo.svg"), "w", encoding="utf-8") as handle:
            handle.write(SVG_TEXT)
        code, _, _ = self.run_tool()
        self.assertEqual(0, code)
        self.assertEqual(["logo.png"], self.names())
        self.assertEqual("PNG", self.image_in_zip("logo.png").format)

    def test_without_an_svg_renderer_the_svg_is_skipped_with_a_clear_message_and_exit_2(self):
        draw(os.path.join(self.input, "gut.jpg"), (600, 400), fmt="JPEG")
        with open(os.path.join(self.input, "logo.svg"), "w", encoding="utf-8") as handle:
            handle.write(SVG_TEXT)
        empty = os.path.join(self._tmp.name, "empty-path")
        os.mkdir(empty)
        previous = os.environ.get("PATH")
        os.environ["PATH"] = empty
        try:
            code, _, stderr = self.run_tool()
        finally:
            if previous is None:
                del os.environ["PATH"]
            else:
                os.environ["PATH"] = previous
        self.assertEqual(2, code)
        self.assertIn("neither rsvg-convert nor ImageMagick", stderr)
        self.assertEqual(["gut.jpg"], self.names(), "the raster image is still prepared")

    def test_more_than_100_images_are_refused(self):
        for i in range(101):
            draw(os.path.join(self.input, "bild-%03d.jpg" % i), (300, 200), fmt="JPEG")
        code, _, stderr = self.run_tool()
        self.assertEqual(2, code)
        self.assertIn("at most 100", stderr)

    def test_the_output_is_deterministic(self):
        draw(os.path.join(self.input, "a.jpg"), (600, 400), fmt="JPEG")
        self.run_tool()
        with open(self.out, "rb") as handle:
            first = handle.read()
        os.remove(self.out)
        self.run_tool()
        with open(self.out, "rb") as handle:
            self.assertEqual(first, handle.read())

    def test_the_slug_rule_is_the_servers_rule(self):
        for good in ("a", "mein-artikel", "zwei_teile", "a1-b2_c3", "x" * 120):
            self.assertTrue(tool.valid_slug(good), good)
        for bad in ("", "A", "-a", "a-", "a--b", "a__b", "a b", "ä", "x" * 121):
            self.assertFalse(tool.valid_slug(bad), bad)


if __name__ == "__main__":
    unittest.main()
