#!/usr/bin/env python3
"""Build the offline preview with no runtime dependencies or network assets."""
from pathlib import Path
import argparse

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, default=root / 'index.html')
args = parser.parse_args()
html = (root / 'shell.html').read_text()
for token, name in [('BASE_CSS', 'base.css'), ('WORKSPACE_CSS', 'workspace.css'),
                    ('CORE_JS', 'core.js'), ('WORKSPACE_JS', 'workspace.js')]:
    html = html.replace('{{' + token + '}}', (root / name).read_text())
assert '{{CORE_JS}}' not in html
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(html)
print(f'Built {args.output} ({len(html.encode()):,} bytes)')
