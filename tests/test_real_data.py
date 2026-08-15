import os
from pathlib import Path

import numpy as np
import pytest

from flimkit_qupath_bridge.server import BridgeState

PTU_PATH = os.environ.get('FLIMKIT_TEST_PTU', '')
QUPATH_PATH = os.environ.get('QUPATH_PATH', '')

pytestmark = pytest.mark.skipif(
    not PTU_PATH or not Path(PTU_PATH).exists() or not QUPATH_PATH,
    reason='set FLIMKIT_TEST_PTU and QUPATH_PATH to run the real-data tests',
)


@pytest.fixture(scope='module')
def intensity_image():
    reader = pytest.importorskip('flimkit.formats.PTU.reader')
    image, _ = reader.get_intensity_image(PTU_PATH)
    return np.asarray(image)


@pytest.fixture(scope='module')
def lifetime_map():
    pytest.importorskip('flimkit.FLIM.fitters')
    from flimkit.FLIM.fitters import fit_per_pixel, fit_summed
    from flimkit.FLIM.irf_tools import gaussian_irf_from_fwhm
    from flimkit.formats.PTU.reader import read_ptu

    stack, meta = read_ptu(PTU_PATH, binning=2)
    tcspc_res = meta['tcspc_resolution']
    n_bins = meta['n_bins']
    decay = stack.sum(axis=(0, 1)).astype(float)
    irf = gaussian_irf_from_fwhm(
        n_bins, tcspc_res, 0.15, max(int(np.argmax(decay)) - 4, 0))
    global_popt, summary = fit_summed(
        decay, tcspc_res, n_bins, irf,
        False, True, True,
        1, 0.2, 6.0,
        optimizer='de', workers=1,
    )
    maps = fit_per_pixel(
        stack, tcspc_res, n_bins, irf,
        False, True, True,
        global_popt, 1,
        min_photons=50,
        tau_min_ns=0.2, tau_max_ns=6.0,
        fit_idx=summary.get('fit_idx'),
        use_gpu=False,
    )
    return np.asarray(maps['tau_mean_int'], dtype=np.float32)


def test_real_intensity_survives_the_trip(serve_state, verify_image, intensity_image):
    state = BridgeState(
        images={'intensity': intensity_image},
        units={'intensity': 'photons'},
    )
    reported = verify_image(QUPATH_PATH, serve_state(state), 'intensity')

    height, width = intensity_image.shape
    assert int(reported['width']) == width
    assert int(reported['height']) == height
    assert reported['type'] == 'FLOAT32'
    assert reported['unit'] == 'photons'
    assert int(reported['nan']) == 0
    assert float(reported['max']) == pytest.approx(float(intensity_image.max()))
    assert float(reported['sum']) == pytest.approx(
        float(intensity_image.sum()), rel=1e-6)


def test_real_lifetime_map_survives_the_trip(serve_state, verify_image, lifetime_map):
    state = BridgeState(
        images={'lifetime': lifetime_map},
        units={'lifetime': 'ns'},
    )
    reported = verify_image(QUPATH_PATH, serve_state(state), 'lifetime')

    height, width = lifetime_map.shape
    finite = np.isfinite(lifetime_map)
    assert int(reported['width']) == width
    assert int(reported['height']) == height
    assert reported['type'] == 'FLOAT32'
    assert reported['unit'] == 'ns'
    assert int(reported['finite']) == int(finite.sum())
    assert int(reported['nan']) == int((~finite).sum())
    assert float(reported['max']) == pytest.approx(
        float(lifetime_map[finite].max()), rel=1e-6)
    assert float(reported['sum']) == pytest.approx(
        float(lifetime_map[finite].sum()), rel=1e-5)
