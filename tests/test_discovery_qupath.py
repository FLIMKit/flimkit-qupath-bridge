import os
import subprocess
from pathlib import Path

import pytest

from flimkit_qupath_bridge import discovery

QUPATH_PATH = os.environ.get('QUPATH_PATH', '')
EXTENSION_JARS = sorted(
    (Path.home() / 'QuPath' / 'v0.7' / 'extensions').glob(
        'qupath-extension-flimkit-bridge-*.jar'))

pytestmark = pytest.mark.skipif(
    not QUPATH_PATH or not EXTENSION_JARS,
    reason='needs QUPATH_PATH and the extension installed in QuPath',
)


def _read_in_qupath(path):
    script = Path(__file__).parents[1] / 'qupath' / 'ReadDiscovery.groovy'
    assert script.exists(), f'missing QuPath script: {script}'
    completed = subprocess.run(
        [QUPATH_PATH, 'script', str(script), '--args', str(path)],
        capture_output=True,
        text=True,
        timeout=600,
    )
    return completed, completed.stdout + completed.stderr


def test_qupath_reads_what_flimkit_wrote(tmp_path):
    target = tmp_path / 'qupath-bridge.json'
    discovery.write('http://127.0.0.1:9123', 'a-real-token', path=target)

    completed, output = _read_in_qupath(target)

    assert completed.returncode == 0, output
    assert 'DISCOVERY_OK' in output, output
    fields = dict(
        field.split('=', 1)
        for field in output.split('DISCOVERY_OK ')[1].splitlines()[0].split(' ')
    )
    assert fields['url'] == 'http://127.0.0.1:9123'
    assert fields['token'] == 'a-real-token'
    assert fields['pid'] == str(os.getpid())
    assert fields['stale'] == 'false'


def test_qupath_sees_a_dead_flimkit_as_stale(tmp_path):
    target = tmp_path / 'qupath-bridge.json'
    discovery.write('http://127.0.0.1:9123', 'tok', pid=999999, path=target)

    completed, output = _read_in_qupath(target)

    assert completed.returncode == 0, output
    assert 'stale=true' in output, output


def test_qupath_refuses_a_foreign_file(tmp_path):
    target = tmp_path / 'qupath-bridge.json'
    target.write_text('{"protocol": "something-else", "url": "x", "token": "y"}')

    completed, output = _read_in_qupath(target)

    assert completed.returncode != 0
    assert 'not a FLIMKit bridge file' in output, output
