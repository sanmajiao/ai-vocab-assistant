"""Build only this project's source; dependencies are installed by users."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path
from core import APP_VERSION

ROOT = Path(__file__).resolve().parent
FILES = (
    '.gitattributes', '.gitignore', 'LICENSE', 'README.md',
    'RELEASE_NOTES.md', 'THIRD_PARTY_NOTICES.md', 'app.ico',
    'app.py', 'build_share.py', 'core.py', 'local_check.py', 'network.py',
    'ocr.py', 'ocr_worker.ps1', 'pipeline.py', 'reader.py', 'windows.py',
    'requirements.txt', 'requirements-ocr.txt',
    'test_unit.py', 'test_state_v5.py', 'test_capture_v5.py',
    'docs/DEVELOPMENT.md', '.github/workflows/check.yml',
    '.github/ISSUE_TEMPLATE/bug_report.yml',
)


def main():
    parser = argparse.ArgumentParser(
        description='Build source ZIP without third-party software or user data')
    parser.parse_args()
    sources = [(name, ROOT / name) for name in FILES]
    for name, source in sources:
        if source.is_symlink() or not source.is_file():
            raise RuntimeError(f'Missing or symbolic-link source file: {name}')
    output = ROOT / 'release' / APP_VERSION
    archive = output / f'ai-vocab-assistant-v{APP_VERSION}-source.zip'
    hashes = output / 'SHA256.txt'
    if archive.exists() or hashes.exists():
        raise RuntimeError('Existing output preserved; use a fresh release directory')
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
        for name, source in sources:
            zipped.write(source, f'ai-vocab-assistant/{name}')
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    hashes.write_text(f'{digest}  {archive.name}\n', encoding='utf-8')
    print(json.dumps({'archive': str(archive), 'source_files': len(FILES),
                      'sha256': digest, 'bundled_third_party': False},
                     ensure_ascii=True))


if __name__ == '__main__':
    main()
