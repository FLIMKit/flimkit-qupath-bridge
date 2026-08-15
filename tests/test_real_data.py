import os
import subprocess
import threading
from pathlib import Path

import numpy as np
import pytest

from flimkit_qupath_bridge.server import BridgeState, create_server

PTU_PATH = os.environ.get('FLIMKIT_TEST_PTU', '')
QUPATH_PATH = os.environ.get('QUPATH_PATH', '')

pytestmark = pytest.mark.skipif(
    not PTU_PATH or not Path(PTU_PATH).exists() or not QUPATH_PATH,
    reason='set FLIMKIT_TEST_PTU and QUPATH_PATH to run the real-data test',
)


@pytest.fixture(scope='module')
def intensity_image():
    reader = pytest.importorskip('flimkit.formats.PTU.reader')
    image, _ = reader.get_intensity_image(PTU_PATH)
    return np.asarray(image)


@pytest.fixture
def running_server(intensity_image):
    state = BridgeState(
        images={'intensity': intensity_image},
        units={'intensity': 'photons'},
    )
    server = create_server('127.0.0.1', 0, 'test-token', state)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = server.server_address
        yield f'http://{host}:{port}', state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_real_ptu_intensity_survives_the_trip(running_server, intensity_image):
    base_url, _ = running_server
    script = Path(__file__).parents[1] / 'qupath' / 'VerifyImage.groovy'
    assert script.exists(), f'missing QuPath script: {script}'

    completed = subprocess.run(
        [
            QUPATH_PATH,
            'script',
            str(script),
            '--args', base_url,
            '--args', 'test-token',
            '--args', 'intensity',
        ],
        capture_output=True,
        text=True,
        timeout=600,
    )

    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output

    line = [row for row in output.splitlines() if 'QUPATH_REAL_OK' in row]
    assert line, output
    reported = dict(
        field.split('=', 1) for field in line[0].split('QUPATH_REAL_OK ')[1].split(' ')
    )

    height, width = intensity_image.shape
    assert int(reported['width']) == width
    assert int(reported['height']) == height
    assert reported['type'] == 'FLOAT32'
    assert reported['unit'] == 'photons'
    assert float(reported['max']) == pytest.approx(float(intensity_image.max()))
    assert float(reported['sum']) == pytest.approx(
        float(intensity_image.sum()), rel=1e-6)
