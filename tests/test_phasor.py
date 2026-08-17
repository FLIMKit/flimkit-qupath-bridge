import numpy as np
import pytest

from flimkit_qupath_bridge import phasor


@pytest.fixture
def two_populations():
    rng = np.random.default_rng(0)
    real = np.empty((16, 16), dtype=float)
    imag = np.empty((16, 16), dtype=float)
    real[:8] = rng.normal(0.30, 0.01, (8, 16))
    imag[:8] = rng.normal(0.40, 0.01, (8, 16))
    real[8:] = rng.normal(0.70, 0.01, (8, 16))
    imag[8:] = rng.normal(0.35, 0.01, (8, 16))
    mean = np.full((16, 16), 100.0)
    return real, imag, mean


def test_cursor_mask_selects_one_population(two_populations):
    real, imag, mean = two_populations

    masks = phasor.cursor_masks(
        real, imag, mean,
        cursors=[{'id': 'c1', 'center_g': 0.30, 'center_s': 0.40, 'radius': 0.05}],
        min_photons=1.0)

    assert list(masks) == ['c1']
    mask = masks['c1']
    assert mask.shape == (16, 16)
    assert mask[:8].all()
    assert not mask[8:].any()


def test_two_cursors_are_separable(two_populations):
    real, imag, mean = two_populations

    masks = phasor.cursor_masks(
        real, imag, mean,
        cursors=[
            {'id': 'a', 'center_g': 0.30, 'center_s': 0.40, 'radius': 0.05},
            {'id': 'b', 'center_g': 0.70, 'center_s': 0.35, 'radius': 0.05},
        ],
        min_photons=1.0)

    assert masks['a'].sum() == 128
    assert masks['b'].sum() == 128
    assert not (masks['a'] & masks['b']).any()


def test_low_photon_pixels_are_excluded(two_populations):
    real, imag, mean = two_populations
    mean = mean.copy()
    mean[0, :] = 0.0

    masks = phasor.cursor_masks(
        real, imag, mean,
        cursors=[{'id': 'c1', 'center_g': 0.30, 'center_s': 0.40, 'radius': 0.05}],
        min_photons=10.0)

    assert not masks['c1'][0].any()
    assert masks['c1'][1:8].all()


def test_nan_phasor_pixels_are_excluded(two_populations):
    real, imag, mean = two_populations
    real = real.copy()
    real[0, 0] = np.nan

    masks = phasor.cursor_masks(
        real, imag, mean,
        cursors=[{'id': 'c1', 'center_g': 0.30, 'center_s': 0.40, 'radius': 0.05}],
        min_photons=1.0)

    assert not masks['c1'][0, 0]


def test_a_polygon_cursor_selects_its_vertices(two_populations):
    real, imag, mean = two_populations

    masks = phasor.cursor_masks(
        real, imag, mean,
        cursors=[{'id': 'poly', 'type': 'polygon',
                  'vertices': [[0.2, 0.3], [0.45, 0.3], [0.45, 0.5], [0.2, 0.5]]}],
        min_photons=1.0)

    assert masks['poly'][:8].all()
    assert not masks['poly'][8:].any()


def test_label_image_numbers_cursors_from_one(two_populations):
    real, imag, mean = two_populations

    labels = phasor.label_image(
        real, imag, mean,
        cursors=[
            {'id': 'a', 'center_g': 0.30, 'center_s': 0.40, 'radius': 0.05},
            {'id': 'b', 'center_g': 0.70, 'center_s': 0.35, 'radius': 0.05},
        ],
        min_photons=1.0)

    assert labels.dtype == np.uint8
    assert set(np.unique(labels)) == {1, 2}
    assert (labels[:8] == 1).all()
    assert (labels[8:] == 2).all()


def test_density_histogram_counts_every_valid_pixel(two_populations):
    real, imag, mean = two_populations

    grid = phasor.density(real, imag, mean, bins=64, min_photons=1.0)

    assert grid['counts'].shape == (64, 64)
    assert grid['counts'].dtype == np.uint32
    assert int(grid['counts'].sum()) == 256
    assert grid['n_in_range'] == 256
    assert grid['g_min'] < grid['g_max']
    assert grid['max_count'] >= 1


def test_density_skips_invalid_pixels(two_populations):
    real, imag, mean = two_populations
    mean = mean.copy()
    mean[0, :] = 0.0

    grid = phasor.density(real, imag, mean, bins=32, min_photons=10.0)

    assert int(grid['counts'].sum()) == 256 - 16


def test_an_unknown_cursor_type_is_refused(two_populations):
    real, imag, mean = two_populations

    with pytest.raises(ValueError, match='unknown cursor type'):
        phasor.cursor_masks(real, imag, mean,
                            cursors=[{'id': 'x', 'type': 'banana'}],
                            min_photons=1.0)


import os
from pathlib import Path

PTU_PATH = os.environ.get('FLIMKIT_TEST_PTU', '')
SDT_PATH = os.environ.get('FLIMKIT_TEST_SDT', '')


@pytest.mark.skipif(not PTU_PATH or not Path(PTU_PATH).exists(),
                    reason='set FLIMKIT_TEST_PTU')
def test_generic_path_matches_flimkits_ptu_phasor():
    """FLIMKit's own entry point is PTU-only. Routing through FLIMFile must
    give the same numbers, or the two front ends would disagree."""
    import io
    import contextlib
    from flimkit.phasor_launcher import _process_ptu

    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        reference = _process_ptu(PTU_PATH, channel=0)
        ours = phasor.compute(PTU_PATH, channel=0)

    np.testing.assert_allclose(
        ours['real'], phasor._first_harmonic(reference['real_cal']),
        equal_nan=True, rtol=1e-12, atol=1e-12)
    np.testing.assert_allclose(
        ours['imag'], phasor._first_harmonic(reference['imag_cal']),
        equal_nan=True, rtol=1e-12, atol=1e-12)
    assert ours['frequency'] == pytest.approx(float(reference['frequency']))


@pytest.mark.skipif(not SDT_PATH or not Path(SDT_PATH).exists(),
                    reason='set FLIMKIT_TEST_SDT')
def test_becker_hickl_sdt_gets_a_phasor():
    found = phasor.compute(SDT_PATH, channel=0)

    assert found['real'].ndim == 2
    assert found['real'].shape == found['imag'].shape == found['mean'].shape
    assert found['frequency'] > 0
    valid = phasor.valid_pixels(found['real'], found['mean'], min_photons=1.0)
    assert valid.any(), 'no pixel had enough photons for a phasor'
