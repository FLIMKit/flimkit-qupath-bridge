import numpy as np
import pytest

from flimkit_qupath_bridge import fitting


def _synthetic_stack(tau_ns=2.0, n_bins=256, tcspc_res=5e-11, shape=(8, 8),
                     photons=200000, background=5.0):
    t_ns = np.arange(n_bins) * tcspc_res * 1e9
    decay = np.exp(-t_ns / tau_ns)
    decay = decay / decay.sum() * photons + background
    stack = np.tile(decay, shape + (1,))
    return np.rint(stack).astype(np.uint32)


def test_defaults_have_values_and_a_schema():
    payload = fitting.defaults()

    assert set(payload) == {'values', 'schema'}
    keys = {entry['key'] for entry in payload['schema']}
    assert {'n_exp', 'tau_min_ns', 'tau_max_ns', 'cost_function'} <= keys
    for entry in payload['schema']:
        assert entry['key'] in payload['values']
        assert entry['type'] in ('int', 'float', 'choice', 'bool')
        assert entry['applies_to']


def test_schema_choices_are_listed_for_choice_fields():
    for entry in fitting.defaults()['schema']:
        if entry['type'] == 'choice':
            assert entry['choices'], f"{entry['key']} has no choices"


def test_merge_params_keeps_defaults_for_absent_keys():
    merged = fitting.merge_params({'n_exp': 3})

    assert merged['n_exp'] == 3
    assert merged['tau_min_ns'] == fitting.defaults()['values']['tau_min_ns']


def test_merge_params_rejects_an_unknown_key():
    with pytest.raises(ValueError, match='unknown fit parameter'):
        fitting.merge_params({'nonsense': 1})


def test_merge_params_rejects_a_bad_component_count():
    with pytest.raises(ValueError, match='n_exp'):
        fitting.merge_params({'n_exp': 9})


def test_a_known_single_exponential_is_recovered():
    stack = _synthetic_stack(tau_ns=2.0)
    mask = np.ones(stack.shape[:2], dtype=bool)

    result = fitting.fit_masked_decay(
        stack, mask, tcspc_res=5e-11, n_bins=256,
        params=fitting.merge_params({'n_exp': 1, 'tau_min_ns': 0.2,
                                     'tau_max_ns': 8.0}))

    assert result['taus_ns'][0] == pytest.approx(2.0, rel=0.1)
    assert result['tau_mean_ns'] == pytest.approx(2.0, rel=0.1)
    assert result['photon_count'] > 0
    assert result['chi2_r'] > 0


def test_mask_selects_only_its_pixels():
    stack = _synthetic_stack(shape=(4, 4))
    mask = np.zeros((4, 4), dtype=bool)
    mask[0, 0] = True

    result = fitting.fit_masked_decay(
        stack, mask, tcspc_res=5e-11, n_bins=256,
        params=fitting.merge_params({'n_exp': 1}))

    assert result['n_pixels'] == 1
    assert result['photon_count'] == int(stack[0, 0].sum())


def test_an_empty_mask_is_refused():
    stack = _synthetic_stack(shape=(4, 4))

    with pytest.raises(ValueError, match='no pixels'):
        fitting.fit_masked_decay(
            stack, np.zeros((4, 4), dtype=bool), tcspc_res=5e-11,
            n_bins=256, params=fitting.merge_params({}))


def test_mask_shape_must_match_the_stack():
    stack = _synthetic_stack(shape=(4, 4))

    with pytest.raises(ValueError, match='does not match'):
        fitting.fit_masked_decay(
            stack, np.ones((8, 8), dtype=bool), tcspc_res=5e-11,
            n_bins=256, params=fitting.merge_params({}))


def test_geojson_becomes_masks_at_the_stack_resolution():
    collection = {
        'type': 'FeatureCollection',
        'features': [{
            'type': 'Feature',
            'properties': {'name': 'Cell 1'},
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[0.0, 0.0], [8.0, 0.0], [8.0, 8.0],
                                 [0.0, 8.0], [0.0, 0.0]]],
            },
        }],
    }

    masks = fitting.masks_from_geojson(collection, image_shape=(16, 16),
                                       binning=2)

    assert len(masks) == 1
    name, mask = masks[0]
    assert name == 'Cell 1'
    assert mask.shape == (8, 8)
    assert mask.any()


def test_geojson_masks_scale_with_binning():
    collection = {
        'type': 'FeatureCollection',
        'features': [{
            'type': 'Feature',
            'properties': {'name': 'Half'},
            'geometry': {
                'type': 'Polygon',
                'coordinates': [[[0.0, 0.0], [16.0, 0.0], [16.0, 8.0],
                                 [0.0, 8.0], [0.0, 0.0]]],
            },
        }],
    }

    unbinned = fitting.masks_from_geojson(collection, (16, 16), binning=1)[0][1]
    binned = fitting.masks_from_geojson(collection, (16, 16), binning=2)[0][1]

    assert unbinned.shape == (16, 16)
    assert binned.shape == (8, 8)
    assert binned.sum() == pytest.approx(unbinned.sum() / 4, rel=0.35)
