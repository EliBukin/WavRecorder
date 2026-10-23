#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('builder', Path(__file__).with_name('prepare-privacy-site.py'))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
ROOT = builder.ROOT
MD = (ROOT / 'docs/privacy-policy.md').read_text()
XML = (ROOT / 'app/src/main/res/values/strings.xml').read_text()
# The policy's current effective date: the tests below change it to check what's refused.
DATE = re.search(r'\*\*Effective date:\*\* (\d{4}-\d{2}-\d{2})', MD)[1]


def approved():
    md = '\n'.join(line for line in MD.splitlines() if not line.startswith('> '))
    return (md.replace('[[PLACEHOLDER: EFFECTIVE DATE]]', '2026-10-11'),
            XML.replace('[[PLACEHOLDER: EFFECTIVE DATE]]', '2026-10-11'))


class PrivacySiteTests(unittest.TestCase):
    def test_draft_refused(self):
        md, xml = approved()
        with self.assertRaises(ValueError):
            builder.validate(md + '\n[[PLACEHOLDER: TEST]]', xml)

    def test_preview_allows_draft(self):
        builder.validate(MD, XML, preview=True)

    def test_completed_policy_passes(self):
        builder.validate(*approved())

    def test_mismatched_dates_refused(self):
        md, xml = approved()
        with self.assertRaises(ValueError):
            builder.validate(md, xml.replace(DATE, '2026-10-12'))

    def test_invalid_date_refused(self):
        md, xml = approved()
        with self.assertRaises(ValueError):
            builder.validate(md.replace(DATE, '2026-02-30'), xml.replace(DATE, '2026-02-30'))

    def test_missing_identity_refused(self):
        md, xml = approved()
        with self.assertRaises(ValueError):
            builder.validate(md.replace('Bukin Mic Recorder', 'Other'), xml)

    def test_only_policy_files_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ('docs/privacy-policy.md', 'app/src/main/res/values/strings.xml', 'scripts/render-privacy-policy.py'):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            policy = root / 'docs/privacy-policy.md'
            policy.write_text(policy.read_text() + '\n> DRAFT: local preview only.\n')
            builder.ROOT = root
            try:
                builder.build(preview=True)
                self.assertEqual({p.name for p in (root / '_privacy_site').iterdir()},
                                 {'index.html', 'privacy-policy.html', '.nojekyll'})
                page = (root / '_privacy_site/index.html').read_text()
                self.assertIn('noindex', page)
                self.assertNotIn('<script', page)
                self.assertIn('not specifically designed for children', page)
                with self.assertRaises(FileExistsError):
                    builder.build(preview=True)
            finally:
                builder.ROOT = ROOT


if __name__ == '__main__':
    unittest.main()
