import json
from pathlib import Path
import tempfile
import unittest

from embed import generate, read_mapping


URL = "https://preview.coo.ee/p/quantity-stepper/r/fixture-1/"


class EmbedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.output = self.root / "html", self.root / "embedded"
        (self.source / "api").mkdir(parents=True)
        self.mapping_path = self.root / "mapping.json"
        self.mapping = {
            "schemaVersion": 1,
            "documentationVersion": "fixture-1",
            "previews": [{"url": URL, "publicationId": "quantity-stepper", "revision": "fixture-1",
                          "title": "Quantity & control", "height": 420, "occurrences": 2}],
        }
        self.original = (
            '<!DOCTYPE html><html><head><title>API</title></head><body>'
            '<pre><code>val example = quantityStepperInputs()</code></pre>'
            f'<p><a href="{URL}">First preview</a></p>'
            f'<p><a href="{URL}">Second preview</a></p>'
            '<p><a href="https://kotlinlang.org/">Kotlin</a></p></body></html>'
        )
        (self.source / "api" / "quantity.html").write_text(self.original)
        (self.source / "index.html").write_text("<html><body>Index without previews</body></html>")
        self.write_mapping()

    def write_mapping(self):
        self.mapping_path.write_text(json.dumps(self.mapping))

    def run_generation(self, **kwargs):
        return generate(self.source, self.output, self.mapping_path, "fixture-1", **kwargs)

    def test_two_embeds_keep_source_links_and_unrelated_pages(self):
        self.assertEqual({"pages": 1, "embeds": 2}, self.run_generation())
        page = (self.output / "api" / "quantity.html").read_text()
        self.assertEqual(page.count('<iframe '), 2)
        self.assertEqual(page.count(f'src="{URL}embed/"'), 2)
        self.assertEqual(page.count('title="Quantity &amp; control"'), 2)
        self.assertIn('loading="lazy"', page)
        self.assertIn('href="../livery-preview.css"', page)
        self.assertIn('quantityStepperInputs()', page)
        self.assertIn(f'<a href="{URL}">First preview</a>', page)
        self.assertIn('Open interactive preview', page)
        self.assertIn(f'src="{URL}thumbnail.png"', page)
        self.assertNotIn('<script', page)
        self.assertEqual((self.source / "api" / "quantity.html").read_text(), self.original)
        self.assertEqual((self.source / "index.html").read_bytes(), (self.output / "index.html").read_bytes())

    def test_missing_or_extra_mapped_links_fail_before_output_changes(self):
        self.run_generation()
        before = (self.output / "api" / "quantity.html").read_bytes()
        (self.source / "api" / "quantity.html").write_text("<html><head></head><body>No mapped links</body></html>")
        with self.assertRaisesRegex(ValueError, "found 0"):
            self.run_generation()
        self.assertEqual(before, (self.output / "api" / "quantity.html").read_bytes())
        (self.source / "api" / "quantity.html").write_text(self.original.replace('</body>', f'<a href="{URL}">Extra</a></body>'))
        with self.assertRaisesRegex(ValueError, "found 3"):
            self.run_generation()
        self.assertEqual(before, (self.output / "api" / "quantity.html").read_bytes())

    def test_release_mapping_must_match_documentation_version(self):
        with self.assertRaisesRegex(ValueError, "version"):
            generate(self.source, self.output, self.mapping_path, "fixture-2")
        self.assertFalse(self.output.exists())

    def test_moving_revision_non_https_credentials_and_query_are_rejected(self):
        invalid = [
            {"url": URL.replace("fixture-1", "latest"), "revision": "latest"},
            {"url": URL.replace("https:", "http:")},
            {"url": URL.replace("https://", "https://user:secret@")},
            {"url": URL + "?token=secret"},
            {"url": URL + "#preview"},
            {"url": URL.replace("fixture-1", "fixture-2")},
            {"url": URL.replace("https://", "https://\n")},
            {"url": URL.replace("preview.coo.ee", "bad host")},
            {"url": URL.replace("preview.coo.ee", "bad\\host")},
        ]
        for changes in invalid:
            with self.subTest(changes=changes):
                original = self.mapping["previews"][0].copy()
                self.mapping["previews"][0].update(changes)
                self.write_mapping()
                with self.assertRaises(ValueError):
                    self.run_generation()
                self.mapping["previews"][0] = original

    def test_loopback_override_changes_embeds_not_original_kdoc_links(self):
        self.run_generation(development_origin="http://127.0.0.1:8042")
        page = (self.output / "api" / "quantity.html").read_text()
        self.assertIn('src="http://127.0.0.1:8042/p/quantity-stepper/r/fixture-1/embed/"', page)
        self.assertIn(f'<a href="{URL}">First preview</a>', page)
        with self.assertRaisesRegex(ValueError, "loopback"):
            self.run_generation(development_origin="https://other.example")

    def test_unrelated_destination_and_symlink_input_are_not_overwritten(self):
        self.output.mkdir()
        (self.output / "keep.txt").write_text("unrelated")
        with self.assertRaisesRegex(ValueError, "Refusing"):
            self.run_generation()
        self.assertEqual((self.output / "keep.txt").read_text(), "unrelated")
        (self.output / "keep.txt").unlink()
        self.output.rmdir()
        (self.source / "linked.txt").symlink_to(self.mapping_path)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.run_generation()
        self.assertFalse(self.output.exists())

    def test_generated_output_can_be_refreshed_but_not_used_as_input(self):
        self.run_generation()
        self.assertEqual({"pages": 1, "embeds": 2}, self.run_generation())
        with self.assertRaisesRegex(ValueError, "already transformed"):
            generate(self.output, self.root / "again", self.mapping_path, "fixture-1")

    def test_titles_are_text_and_bad_dimensions_are_refused(self):
        self.mapping["previews"][0]["title"] = '<img src=x onerror="alert(1)">'
        self.write_mapping()
        self.run_generation()
        page = (self.output / "api" / "quantity.html").read_text()
        self.assertIn("&lt;img", page)
        self.assertNotIn('<img src=x', page)
        self.mapping["previews"][0]["height"] = True
        self.write_mapping()
        with self.assertRaisesRegex(ValueError, "height"):
            read_mapping(self.mapping_path, "fixture-1")


if __name__ == "__main__":
    unittest.main()
