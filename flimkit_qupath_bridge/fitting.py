import numpy as np

SCHEMA = (
    {'key': 'n_exp', 'label': 'Exponentials', 'type': 'int', 'min': 1, 'max': 3,
     'applies_to': ('roi', 'per_pixel'), 'advanced': False, 'default': 2},
    {'key': 'tau_min_ns', 'label': 'Minimum tau (ns)', 'type': 'float',
     'min': 0.01, 'max': 100.0, 'applies_to': ('roi', 'per_pixel'),
     'advanced': False, 'default': 0.2},
    {'key': 'tau_max_ns', 'label': 'Maximum tau (ns)', 'type': 'float',
     'min': 0.05, 'max': 200.0, 'applies_to': ('roi', 'per_pixel'),
     'advanced': False, 'default': 6.0},
    {'key': 'binning', 'label': 'Binning', 'type': 'choice',
     'choices': (1, 2, 4, 8), 'applies_to': ('roi', 'per_pixel'),
     'advanced': False, 'default': 1},
    {'key': 'min_photons', 'label': 'Minimum photons per pixel', 'type': 'int',
     'min': 0, 'max': 100000, 'applies_to': ('roi', 'per_pixel'),
     'advanced': False, 'default': 5},
    {'key': 'irf_strategy', 'label': 'Instrument response', 'type': 'choice',
     'choices': ('machine_irf', 'machine_irf_sigma_full', 'machine_irf_sigma_half',
                 'session', 'gaussian', 'scatter_file', 'pck'),
     'applies_to': ('roi', 'per_pixel'), 'advanced': False,
     'default': 'machine_irf'},
    {'key': 'irf_path', 'label': 'IRF file', 'type': 'path',
     'applies_to': ('roi', 'per_pixel'), 'advanced': False, 'default': ''},
    {'key': 'irf_fwhm_ns', 'label': 'Gaussian IRF FWHM (ns)', 'type': 'float',
     'min': 0.01, 'max': 5.0, 'applies_to': ('roi', 'per_pixel'),
     'advanced': True, 'default': 0.2},
    {'key': 'channel', 'label': 'Channel', 'type': 'int', 'min': 0, 'max': 16,
     'applies_to': ('roi', 'per_pixel'), 'advanced': False, 'default': 0},
    {'key': 'cost_function', 'label': 'Cost function', 'type': 'choice',
     'choices': ('poisson', 'chi2'), 'applies_to': ('roi', 'per_pixel'),
     'advanced': True, 'default': 'poisson'},
    {'key': 'fit_bg', 'label': 'Fit background', 'type': 'bool',
     'applies_to': ('roi', 'per_pixel'), 'advanced': True, 'default': True},
    {'key': 'fit_sigma', 'label': 'Fit IRF broadening', 'type': 'bool',
     'applies_to': ('roi', 'per_pixel'), 'advanced': True, 'default': False},
)


def defaults():
    values = {entry['key']: entry['default'] for entry in SCHEMA}
    schema = []
    for entry in SCHEMA:
        described = {
            'key': entry['key'],
            'label': entry['label'],
            'type': entry['type'],
            'applies_to': list(entry['applies_to']),
            'advanced': entry['advanced'],
        }
        for optional in ('min', 'max'):
            if optional in entry:
                described[optional] = entry[optional]
        if entry['type'] == 'choice':
            described['choices'] = list(entry['choices'])
        schema.append(described)
    return {'values': values, 'schema': schema}


def merge_params(supplied):
    known = {entry['key']: entry for entry in SCHEMA}
    merged = defaults()['values']
    for key, value in (supplied or {}).items():
        if key not in known:
            raise ValueError(f'unknown fit parameter: {key}')
        merged[key] = value
    _validate(merged, known)
    return merged


def _validate(merged, known):
    for key, entry in known.items():
        value = merged[key]
        if entry['type'] == 'int':
            value = int(value)
        elif entry['type'] == 'float':
            value = float(value)
        elif entry['type'] == 'bool':
            value = bool(value)
        elif entry['type'] == 'path':
            value = str(value or '')
        elif entry['type'] == 'choice' and value not in entry['choices']:
            raise ValueError(
                f'{key} must be one of {list(entry["choices"])}, got {value!r}')
        if 'min' in entry and value < entry['min']:
            raise ValueError(f'{key} must be at least {entry["min"]}, got {value}')
        if 'max' in entry and value > entry['max']:
            raise ValueError(f'{key} must be at most {entry["max"]}, got {value}')
        merged[key] = value
    if merged['tau_min_ns'] >= merged['tau_max_ns']:
        raise ValueError('tau_min_ns must be below tau_max_ns')


def masks_from_geojson(collection, image_shape, binning=1):
    from flimkit.UI.roi_tools import RoiManager
    manager = RoiManager()
    scaled = _scale_collection(collection, binning)
    ids = manager.add_geojson(scaled, mode='replace')
    height, width = image_shape[0] // binning, image_shape[1] // binning
    found = []
    for region_id in ids:
        region = manager.get_region(region_id)
        mask = manager.compute_region_mask(region_id, (height, width))
        if mask is None:
            continue
        found.append((region.get('name', f'Region {region_id}'), mask))
    return found


def _scale_collection(collection, binning):
    if binning == 1:
        return collection
    scaled = {'type': 'FeatureCollection', 'features': []}
    for feature in collection.get('features', []):
        geometry = feature.get('geometry') or {}
        rings = geometry.get('coordinates') or []
        moved = [[[x / binning, y / binning] for x, y in ring] for ring in rings]
        scaled['features'].append({
            **feature,
            'geometry': {**geometry, 'coordinates': moved},
        })
    return scaled


def build_irf(n_bins, tcspc_res, decay, cached=None, params=None):
    from flimkit_qupath_bridge import irf as irf_module
    params = params or {}
    strategy = params.get('irf_strategy', 'machine_irf')
    if strategy == 'session' and cached is None:
        strategy = 'machine_irf'
    return irf_module.build(
        strategy, n_bins, tcspc_res, decay,
        path=(params.get('irf_path') or None),
        session_irf=cached,
        fwhm_ns=params.get('irf_fwhm_ns', 0.2))


def fit_masked_decay(stack, mask, tcspc_res, n_bins, params, irf_prompt=None):
    from flimkit.FLIM.fitters import fit_summed
    stack = np.asarray(stack)
    mask = np.asarray(mask, dtype=bool)
    if mask.shape != stack.shape[:2]:
        raise ValueError(
            f'mask shape {mask.shape} does not match stack shape {stack.shape[:2]}')
    if not mask.any():
        raise ValueError('the region selects no pixels')
    if int(params.get('min_photons', 0)):
        bright = stack.sum(axis=2) >= int(params['min_photons'])
        mask = mask & bright
        if not mask.any():
            raise ValueError(
                'no pixels in the region reach the minimum photon count')
    decay = stack[mask].sum(axis=0).astype(float)
    irf, irf_source = build_irf(n_bins, tcspc_res, decay, cached=irf_prompt,
                                params=params)
    popt, summary = fit_summed(
        decay, tcspc_res, n_bins, irf,
        False, bool(params['fit_bg']), bool(params['fit_sigma']),
        int(params['n_exp']),
        float(params['tau_min_ns']),
        float(params['tau_max_ns']),
        cost_function=params['cost_function'],
    )
    taus = [float(t) for t in summary['taus_ns']]
    amps = [float(a) for a in summary['amps']]
    total = float(np.sum(amps))
    tau_mean = float(np.dot(amps, taus) / total) if total else None
    return {
        'taus_ns': taus,
        'amps': amps,
        'fractions': [float(f) for f in summary['fractions']],
        'tau_mean_ns': tau_mean,
        'tau_mean_amp_ns': float(summary['tau_mean_amp_ns']),
        'tau_mean_int_ns': float(summary['tau_mean_int_ns']),
        'chi2_r': float(summary['reduced_chi2']),
        'chi2_r_tail': float(summary['reduced_chi2_tail']),
        'photon_count': int(decay.sum()),
        'n_pixels': int(mask.sum()),
        'irf_source': irf_source,
        'n_exp': int(params['n_exp']),
    }


def fit_pixels(stack, tcspc_res, n_bins, params, irf_prompt=None, bands=8,
               progress=None, cancel=None):
    """Runs fit_per_pixel over row bands.

    The GPU backends accept a progress_callback and never call it, so a single
    call reports nothing and cannot be interrupted. Banding gives a progress
    tick and a cancellation point per band on every backend, and caps peak
    memory. global_popt is fitted once over the whole field so the bands stay
    independent of each other.
    """
    from flimkit.FLIM.fitters import fit_per_pixel, fit_summed
    stack = np.asarray(stack)
    height = stack.shape[0]
    bands = max(1, min(int(bands), height))
    summed = stack.reshape(-1, stack.shape[2]).sum(axis=0).astype(float)
    irf, irf_source = build_irf(n_bins, tcspc_res, summed, cached=irf_prompt,
                                params=params)
    global_popt, summary = fit_summed(
        summed, tcspc_res, n_bins, irf,
        False, bool(params['fit_bg']), bool(params['fit_sigma']),
        int(params['n_exp']),
        float(params['tau_min_ns']),
        float(params['tau_max_ns']),
        cost_function=params['cost_function'],
    )
    edges = np.linspace(0, height, bands + 1).astype(int)
    collected = []
    for index in range(bands):
        if cancel is not None and cancel.is_set():
            return None
        start, stop = edges[index], edges[index + 1]
        if start == stop:
            continue
        maps = fit_per_pixel(
            stack[start:stop], tcspc_res, n_bins, irf,
            False, bool(params['fit_bg']), bool(params['fit_sigma']),
            global_popt, int(params['n_exp']),
            min_photons=int(params['min_photons']),
            tau_min_ns=float(params['tau_min_ns']),
            tau_max_ns=float(params['tau_max_ns']),
            fit_idx=summary.get('fit_idx'),
            use_gpu=False,
        )
        collected.append(maps)
        if progress is not None:
            progress(index + 1, bands, f'band {index + 1} of {bands}')
    if not collected:
        return None
    merged = {}
    for name in collected[0]:
        pieces = [part[name] for part in collected]
        merged[name] = np.concatenate(pieces, axis=0)
    return {
        'maps': merged,
        'global': {
            'taus_ns': [float(t) for t in summary['taus_ns']],
            'amps': [float(a) for a in summary['amps']],
            'fractions': [float(f) for f in summary['fractions']],
            'chi2_r': float(summary['reduced_chi2']),
            'irf_source': irf_source,
            'n_exp': int(params['n_exp']),
        },
        'bands': bands,
    }
