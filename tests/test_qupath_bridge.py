import os
import subprocess
import threading
from pathlib import Path

import numpy as np
import pytest

from flimkit_qupath_bridge.server import BridgeState, create_server


@pytest.fixture
def running_server():
    state = BridgeState(
        images={
            'intensity': np.arange(35, dtype=np.float32).reshape(5, 7),
            'lifetime': np.arange(35, dtype=np.float32).reshape(5, 7) / 10.0,
        },
        units={'intensity': 'photons', 'lifetime': 'ns'},
        exported_rois={
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'properties': {'objectType': 'annotation', 'name': 'FLIMKit rect'},
                    'geometry': {
                        'type': 'Polygon',
                        'coordinates': [[
                            [0.0, 0.0], [4.0, 0.0], [4.0, 3.0], [0.0, 3.0], [0.0, 0.0],
                        ]],
                    },
                },
                {
                    'type': 'Feature',
                    'properties': {'objectType': 'annotation', 'name': 'FLIMKit triangle'},
                    'geometry': {
                        'type': 'Polygon',
                        'coordinates': [[
                            [1.0, 1.0], [5.0, 1.0], [3.0, 4.0], [1.0, 1.0],
                        ]],
                    },
                },
            ],
        },
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


def test_installed_qupath_exchanges_images_and_rois(running_server):
    qupath_path = os.environ.get('QUPATH_PATH')
    if not qupath_path:
        pytest.skip('set QUPATH_PATH to run the live QuPath test')
    script = Path(__file__).parents[1] / 'qupath' / 'QuPathBridge.groovy'
    assert script.exists(), f'missing QuPath script: {script}'
    base_url, state = running_server

    completed = subprocess.run(
        [
            qupath_path,
            'script',
            str(script),
            '--args', base_url,
            '--args', 'test-token',
        ],
        capture_output=True,
        text=True,
        timeout=300,
    )

    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output
    assert 'QUPATH_IMAGES_OK intensity=34.0 lifetime=3.4 lifetime_unit=ns' in output, output
    assert 'QUPATH_ROI_POST_OK features=1' in output, output
    assert 'QUPATH_IMPORT_OK objects=2' in output, output

    assert len(state.received_rois) == 1
    collection = state.received_rois[0]
    assert collection['type'] == 'FeatureCollection'
    feature = collection['features'][0]
    assert feature['properties']['name'] == 'QuPath polygon'
    ring = feature['geometry']['coordinates'][0]
    assert ring[0] == ring[-1]
    assert {tuple(point) for point in ring} == {(1.25, 2.5), (4.5, 2.5), (3.0, 4.0)}
