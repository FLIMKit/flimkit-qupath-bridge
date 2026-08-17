import os
import subprocess
from pathlib import Path

import pytest

from flimkit_qupath_bridge.server import BridgeState

QUPATH_PATH = os.environ.get('QUPATH_PATH', '')
PTU_PATH = os.environ.get('FLIMKIT_TEST_PTU', '')
SDT_PATH = os.environ.get('FLIMKIT_TEST_SDT', '')
EXTENSION_JARS = sorted(
    (Path.home() / 'QuPath' / 'v0.7' / 'extensions').glob(
        'qupath-extension-flimkit-bridge-*.jar'))

pytestmark = pytest.mark.skipif(
    not QUPATH_PATH or not EXTENSION_JARS or not PTU_PATH or not SDT_PATH
    or not Path(PTU_PATH).exists() or not Path(SDT_PATH).exists(),
    reason='needs QUPATH_PATH, the extension installed, and both test files',
)


def _identify_in_qupath(base_url, paths):
    script = Path(__file__).parents[1] / 'qupath' / 'IdentifyFiles.groovy'
    assert script.exists(), f'missing QuPath script: {script}'
    completed = subprocess.run(
        [
            QUPATH_PATH, 'script', str(script),
            '--args', base_url,
            '--args', 'test-token',
            '--args', ';'.join(paths),
        ],
        capture_output=True,
        text=True,
        timeout=600,
    )
    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output
    assert 'IDENTIFY_DONE' in output, output
    reported = {}
    for line in output.splitlines():
        if not line.startswith('IDENTIFY '):
            continue
        fields = dict(f.split('=', 1) for f in line.split('IDENTIFY ')[1].split(' '))
        reported[fields['name']] = fields
    return reported, output


def test_qupath_identifies_ptu_and_sdt(serve_state, tmp_path):
    import numpy as np
    import tifffile

    decoy = tmp_path / 'ordinary.tiff'
    tifffile.imwrite(decoy, np.zeros((4, 4), dtype=np.uint16))

    base_url = serve_state(BridgeState(images={}))
    reported, output = _identify_in_qupath(
        base_url, [PTU_PATH, SDT_PATH, str(decoy)])

    assert 'FORMATS_OK' in output

    ptu = reported[Path(PTU_PATH).name]
    assert ptu['recognised'] == 'true'
    assert ptu['format'] == 'ptu'
    assert ptu['modality'] == 'time'
    assert ptu['ambiguous'] == 'false'

    sdt = reported[Path(SDT_PATH).name]
    assert sdt['recognised'] == 'true'
    assert sdt['format'] == 'bh_sdt'
    assert sdt['modality'] == 'time'
    assert sdt['ambiguous'] == 'false'

    plain = reported['ordinary.tiff']
    assert plain['recognised'] == 'false'
    assert plain['ambiguous'] == 'true'
