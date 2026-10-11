#!/usr/bin/env python3
"""Build a privacy-only Pages artifact. Drafts require explicit local preview mode."""
import argparse
import datetime
import importlib.util
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def validate(markdown, app_xml, preview=False):
    policy = ET.fromstring(app_xml).find("./string[@name='privacy_policy_html']")
    if policy is None:
        raise ValueError('In-app privacy policy is missing')
    app = ''.join(policy.itertext())
    for text in (markdown, app):
        for required in ('Bukin Mic Recorder', 'bukin', 'dev.mechanics998877@gmail.com',
                         'general audience', 'not specifically designed for children'):
            if required not in text:
                raise ValueError('Missing approved identity or audience: ' + required)
    if not preview:
        for text in (markdown, app):
            if '[[PLACEHOLDER' in text or re.search(r'\bDRAFT\b', text, re.I):
                raise ValueError('Draft or unresolved details: obtain owner approval before publishing')
        date = re.search(r'\*\*Effective date:\*\* (\d{4}-\d{2}-\d{2})\b', markdown)
        if not date:
            raise ValueError('Effective date must be YYYY-MM-DD')
        datetime.date.fromisoformat(date[1])
        if 'Effective date: ' + date[1] + '<br>' not in app:
            raise ValueError('Effective dates differ between app and public policy')


def build(preview=False):
    markdown = (ROOT / 'docs/privacy-policy.md').read_text(encoding='utf-8')
    app_xml = (ROOT / 'app/src/main/res/values/strings.xml').read_text(encoding='utf-8')
    validate(markdown, app_xml, preview)
    spec = importlib.util.spec_from_file_location('privacy_renderer', ROOT / 'scripts/render-privacy-policy.py')
    renderer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)
    page = renderer.render_page(markdown)
    output = ROOT / '_privacy_site'
    # Never sweep or upload a pre-existing directory that could hold unrelated files.
    output.mkdir(exist_ok=False)
    (output / 'index.html').write_text(page, encoding='utf-8')
    (output / 'privacy-policy.html').write_text(page, encoding='utf-8')
    (output / '.nojekyll').touch()
    print('Prepared only privacy HTML in', output)
    if preview:
        print('LOCAL PREVIEW ONLY — not approved for publication')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preview', action='store_true', help='Allow a draft for local review only')
    args = parser.parse_args()
    try:
        build(args.preview)
    except (ValueError, FileExistsError) as exc:
        parser.exit(1, str(exc) + '\n')
