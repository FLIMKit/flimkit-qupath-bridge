import re
from io import BytesIO
from urllib.parse import parse_qs, urlparse

import numpy as np
import tifffile

from flimkit_qupath_bridge.datasets import StackTooLarge

PLANE_RE = re.compile(r'^/v1/datasets/([^/]+)/planes/([^/]+)\.tif$')
PLANES_RE = re.compile(r'^/v1/datasets/([^/]+)/planes$')
DATASET_RE = re.compile(r'^/v1/datasets/([^/]+)$')

UNITS = {'intensity': 'photons'}


class RouteError(Exception):

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def _registry(state):
    registry = getattr(state, 'datasets', None)
    if registry is None:
        raise RouteError(503, 'this FLIMKit has no dataset registry')
    return registry


def open_dataset(state, payload):
    from flimkit_qupath_bridge import formats
    registry = _registry(state)
    path = (payload or {}).get('path')
    formats.identify(path)
    channel = (payload or {}).get('channel')
    try:
        ident = registry.open(path, channel=channel)
    except Exception as exc:
        raise RouteError(400, str(exc))
    return registry.metadata(ident)


def list_datasets(state):
    return {'datasets': _registry(state).list()}


def dataset(state, ident):
    registry = _registry(state)
    try:
        return registry.metadata(ident)
    except KeyError:
        raise RouteError(404, f'no such dataset: {ident}')


def close_dataset(state, ident):
    registry = _registry(state)
    try:
        registry.metadata(ident)
    except KeyError:
        raise RouteError(404, f'no such dataset: {ident}')
    return {'closed': registry.close(ident)}


def planes(state, ident):
    registry = _registry(state)
    try:
        meta = registry.metadata(ident)
    except KeyError:
        raise RouteError(404, f'no such dataset: {ident}')
    held = {p['id']: p for p in registry.planes(ident)}
    listed = []
    for name in registry.plane_names(ident):
        entry = held.get(name)
        if entry is None:
            listed.append({
                'id': name,
                'unit': UNITS.get(name, ''),
                'dtype': 'uint16',
                'shape': [meta['height'], meta['width']],
            })
        else:
            listed.append(entry)
    return {'planes': listed}


def plane_tiff(state, ident, name, query):
    from flimkit_qupath_bridge.server import encode_image
    registry = _registry(state)
    try:
        registry.metadata(ident)
    except KeyError:
        raise RouteError(404, f'no such dataset: {ident}')
    options = parse_qs(query)
    binning = int(options.get('binning', ['1'])[0])
    if binning < 1:
        raise RouteError(400, 'binning must be 1 or more')
    if name == 'intensity':
        try:
            array = registry.intensity(ident, binning=binning)
        except StackTooLarge as exc:
            raise RouteError(413, str(exc))
    else:
        array = registry.plane(ident, name)
        if array is None:
            raise RouteError(404, f'no such plane: {name}')
    array = np.asarray(array)
    unit = UNITS.get(name) or registry.plane_unit(ident, name)
    array = _crop(array, options)
    buffer = BytesIO()
    tifffile.imwrite(buffer, encode_image(name, array))
    return buffer.getvalue(), unit, binning, array.shape


def _crop(array, options):
    wanted = [k for k in ('x', 'y', 'w', 'h') if k in options]
    if not wanted:
        return array
    if len(wanted) != 4:
        raise RouteError(400, 'x, y, w and h must all be given together')
    x = int(options['x'][0])
    y = int(options['y'][0])
    w = int(options['w'][0])
    h = int(options['h'][0])
    if x < 0 or y < 0 or w <= 0 or h <= 0:
        raise RouteError(400, 'x and y must be positive and w and h non-zero')
    height, width = array.shape[:2]
    if x + w > width or y + h > height:
        raise RouteError(
            400,
            f'requested region {w}x{h} at ({x},{y}) falls outside {width}x{height}')
    return array[y:y + h, x:x + w]


FIT_ROI_RE = re.compile(r'^/v1/datasets/([^/]+)/fit/roi$')


def fit_defaults(state):
    from flimkit_qupath_bridge import fitting
    return fitting.defaults()


def fit_rois(state, ident, payload):
    from flimkit_qupath_bridge import fitting
    registry = _registry(state)
    try:
        meta = registry.metadata(ident)
    except KeyError:
        raise RouteError(404, f'no such dataset: {ident}')
    payload = payload or {}
    collection = payload.get('rois')
    if not isinstance(collection, dict) or collection.get('type') != 'FeatureCollection':
        raise RouteError(400, 'rois must be a GeoJSON FeatureCollection')
    try:
        params = fitting.merge_params(payload.get('params'))
    except ValueError as exc:
        raise RouteError(400, str(exc))
    binning = int(params['binning'])
    try:
        stack = registry.stack(ident, binning=binning)
    except StackTooLarge as exc:
        raise RouteError(413, str(exc))
    try:
        masks = fitting.masks_from_geojson(
            collection, (meta['height'], meta['width']), binning=binning)
    except Exception as exc:
        raise RouteError(400, f'could not read the ROIs: {exc}')
    if not masks:
        raise RouteError(400, 'no usable regions in the payload')
    irf = _session_irf(state, meta['path'])
    results = []
    for name, mask in masks:
        try:
            found = fitting.fit_masked_decay(
                stack, mask, meta['tcspc_res'], meta['n_bins'], params,
                irf_prompt=irf)
        except ValueError as exc:
            results.append({'name': name, 'error': str(exc)})
            continue
        found['name'] = name
        results.append(found)
    return {'dataset': ident, 'binning': binning, 'results': results,
            'params_used': params}


def _session_irf(state, path):
    app = getattr(state, 'app', None)
    if app is None:
        return None
    preview = getattr(app, '_fov_preview', None)
    if preview is None:
        return None
    import os
    open_path = getattr(preview, '_ptu_path', None)
    if not open_path or os.path.realpath(open_path) != os.path.realpath(path):
        return None
    return getattr(preview, '_irf_prompt', None)


PHASOR_RE = re.compile(r'^/v1/datasets/([^/]+)/phasor$')
PHASOR_POINTS_RE = re.compile(r'^/v1/datasets/([^/]+)/phasor/points$')
PHASOR_MASK_RE = re.compile(r'^/v1/datasets/([^/]+)/phasor/mask$')


def _phasor_state(state, ident):
    from flimkit_qupath_bridge import phasor as phasor_module
    registry = _registry(state)
    try:
        meta = registry.metadata(ident)
    except KeyError:
        raise RouteError(404, f'no such dataset: {ident}')
    if meta['format'] != 'ptu':
        raise RouteError(
            409,
            f"phasor is only available for PicoQuant PTU files, not {meta['format']}")
    held = getattr(state, '_phasor_cache', None)
    if held is None:
        held = {}
        state._phasor_cache = held
    found = held.get(ident)
    if found is None:
        try:
            found = phasor_module.compute(meta['path'], channel=meta['channel'])
        except Exception as exc:
            raise RouteError(500, f'phasor failed: {exc}')
        found['binning'] = DatasetRegistryBinning(meta, found['real'].shape)
        held[ident] = found
    return meta, found


def DatasetRegistryBinning(meta, phasor_shape):
    from flimkit_qupath_bridge.datasets import DatasetRegistry
    try:
        return DatasetRegistry.infer_binning(
            (meta['height'], meta['width']), phasor_shape)
    except ValueError:
        return None


def phasor_summary(state, ident):
    meta, found = _phasor_state(state, ident)
    return {
        'dataset': ident,
        'width': int(found['real'].shape[1]),
        'height': int(found['real'].shape[0]),
        'binning': found['binning'],
        'frequency_hz': found['frequency'],
        'channel': found['channel'],
        'calibrated': found['calibrated'],
    }


def phasor_points(state, ident, query):
    from flimkit_qupath_bridge import phasor as phasor_module
    options = parse_qs(query)
    bins = int(options.get('bins', ['256'])[0])
    if bins < 8 or bins > 1024:
        raise RouteError(400, 'bins must be between 8 and 1024')
    min_photons = float(options.get('min_photons', ['0.01'])[0])
    _, found = _phasor_state(state, ident)
    payload = phasor_module.density_payload(
        found['real'], found['imag'], found['mean'],
        bins=bins, min_photons=min_photons)
    payload['dataset'] = ident
    return payload


def phasor_mask(state, ident, payload):
    from flimkit_qupath_bridge import phasor as phasor_module
    _, found = _phasor_state(state, ident)
    payload = payload or {}
    cursors = payload.get('cursors')
    if not isinstance(cursors, list) or not cursors:
        raise RouteError(400, 'cursors must be a non-empty list')
    min_photons = float(payload.get('min_photons', 0.01))
    try:
        masks = phasor_module.cursor_masks(
            found['real'], found['imag'], found['mean'], cursors, min_photons)
    except ValueError as exc:
        raise RouteError(400, str(exc))
    counts = [
        {'id': identifier, 'n_pixels': int(mask.sum())}
        for identifier, mask in masks.items()
    ]
    if payload.get('output') == 'labels':
        labels = phasor_module.label_image(
            found['real'], found['imag'], found['mean'], cursors, min_photons)
        return {'dataset': ident, 'binning': found['binning'],
                'cursors': counts, 'labels': _encode_labels(labels),
                'width': int(labels.shape[1]), 'height': int(labels.shape[0])}
    return {'dataset': ident, 'binning': found['binning'], 'cursors': counts}


def _encode_labels(labels):
    import base64
    return base64.b64encode(np.ascontiguousarray(labels).tobytes()).decode('ascii')
