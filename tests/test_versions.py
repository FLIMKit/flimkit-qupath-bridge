import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / 'qupath-extension' / 'build.gradle.kts'
JAVA = (ROOT / 'qupath-extension' / 'src' / 'main' / 'java' / 'io' / 'github'
        / 'flimkit' / 'bridge' / 'FlimKitBridgeExtension.java')
CITATION = ROOT / 'CITATION.cff'


def _packaged():
    found = re.search(r"^version\s*=\s*'([^']+)'",
                      (ROOT / 'pyproject.toml').read_text(), re.M)
    return found.group(1) if found else None


def _citation():
    found = re.search(r'^version:\s*(\S+)\s*$', CITATION.read_text(), re.M)
    return found.group(1) if found else None


def _gradle():
    found = re.search(r'^\s*version\s*=\s*"([^"]+)"', GRADLE.read_text(), re.M)
    return found.group(1) if found else None


def _extension():
    found = re.search(r'EXTENSION_VERSION\s*=\s*"([^"]+)"', JAVA.read_text())
    return found.group(1) if found else None


@pytest.mark.parametrize('name, reader', [
    ('qupath-extension/build.gradle.kts', _gradle),
    ('EXTENSION_VERSION in FlimKitBridgeExtension.java', _extension),
    ('CITATION.cff', _citation),
])
def test_every_half_carries_the_same_version(name, reader):
    packaged = _packaged()
    assert packaged, 'pyproject.toml has no version'
    found = reader()
    assert found == packaged, (
        f'{name} says {found} and pyproject.toml says {packaged}; QuPath '
        f'compares EXTENSION_VERSION against bridge_version, so a stale one '
        f'makes the mismatch warning wrong')


def test_the_reported_version_is_the_packaged_one():
    from flimkit_qupath_bridge.version import report

    assert report()['bridge_version'] == _packaged()


def test_the_citation_file_names_every_author_the_zenodo_record_does():
    text = CITATION.read_text()
    for orcid in ('0009-0006-2990-649X', '0000-0003-4605-1682'):
        assert orcid in text, (
            f'{orcid} is on the Zenodo record for this package but not in '
            f'CITATION.cff, and a release mints its metadata from the file')
