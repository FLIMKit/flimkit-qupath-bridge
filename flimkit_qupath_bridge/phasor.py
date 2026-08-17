import base64

import numpy as np

DEFAULT_MIN_PHOTONS = 0.01


def valid_pixels(real, mean, min_photons=DEFAULT_MIN_PHOTONS):
    real = np.asarray(real, dtype=float)
    mean = np.asarray(mean, dtype=float)
    return (mean >= min_photons) & ~np.isnan(real)


def cursor_masks(real, imag, mean, cursors, min_photons=DEFAULT_MIN_PHOTONS):
    from phasorpy.cursor import mask_from_elliptic_cursor
    real = np.asarray(real, dtype=float)
    imag = np.asarray(imag, dtype=float)
    valid = valid_pixels(real, mean, min_photons)
    found = {}
    for index, cursor in enumerate(cursors or []):
        kind = cursor.get('type', 'ellipse')
        identifier = cursor.get('id', f'c{index + 1}')
        if kind == 'ellipse':
            radius = float(cursor.get('radius', 0.05))
            minor = cursor.get('radius_minor')
            mask = mask_from_elliptic_cursor(
                real, imag,
                np.array([float(cursor['center_g'])]),
                np.array([float(cursor['center_s'])]),
                radius=radius,
                radius_minor=radius if minor is None else float(minor),
                angle=cursor.get('angle_mode', 'semicircle'),
            )
            if mask.ndim > real.ndim:
                mask = mask[0]
        elif kind == 'polygon':
            mask = _polygon_mask(real, imag, cursor.get('vertices') or [])
        else:
            raise ValueError(f'unknown cursor type: {kind}')
        found[identifier] = np.asarray(mask, dtype=bool) & valid
    return found


def _polygon_mask(real, imag, vertices):
    from matplotlib.path import Path as MplPath
    if len(vertices) < 3:
        raise ValueError('a polygon cursor needs at least three vertices')
    points = np.column_stack([real.ravel(), imag.ravel()])
    inside = MplPath([(float(g), float(s)) for g, s in vertices]).contains_points(points)
    return inside.reshape(real.shape)


def label_image(real, imag, mean, cursors, min_photons=DEFAULT_MIN_PHOTONS):
    masks = cursor_masks(real, imag, mean, cursors, min_photons)
    labels = np.zeros(np.asarray(real).shape, dtype=np.uint8)
    for index, cursor in enumerate(cursors or []):
        identifier = cursor.get('id', f'c{index + 1}')
        labels[masks[identifier] & (labels == 0)] = index + 1
    return labels


def density(real, imag, mean, bins=256, min_photons=DEFAULT_MIN_PHOTONS,
            g_range=None, s_range=None):
    real = np.asarray(real, dtype=float)
    imag = np.asarray(imag, dtype=float)
    valid = valid_pixels(real, mean, min_photons)
    g = real[valid]
    s = imag[valid]
    if g_range is None:
        g_range = (-0.05, 1.05)
    if s_range is None:
        s_range = (-0.05, 0.65)
    counts, _, _ = np.histogram2d(
        s, g, bins=bins, range=[list(s_range), list(g_range)])
    counts = counts.astype(np.uint32)
    return {
        'bins': int(bins),
        'counts': counts,
        'g_min': float(g_range[0]),
        'g_max': float(g_range[1]),
        's_min': float(s_range[0]),
        's_max': float(s_range[1]),
        'max_count': int(counts.max()) if counts.size else 0,
        'n_valid': int(valid.sum()),
        'n_in_range': int(counts.sum()),
    }


def density_payload(real, imag, mean, bins=256, min_photons=DEFAULT_MIN_PHOTONS):
    grid = density(real, imag, mean, bins=bins, min_photons=min_photons)
    payload = dict(grid)
    payload['counts'] = base64.b64encode(
        np.ascontiguousarray(grid['counts']).tobytes()).decode('ascii')
    return payload


def _first_harmonic(array):
    """phasorpy returns a leading harmonic axis, so real_cal on a single
    harmonic arrives as (1, Y, X) rather than (Y, X)."""
    array = np.asarray(array, dtype=float)
    while array.ndim > 2 and array.shape[0] == 1:
        array = array[0]
    if array.ndim != 2:
        raise ValueError(f'expected a 2-D phasor map, got shape {array.shape}')
    return array


def compute(path, channel=None, irf_path=None):
    from flimkit.phasor_launcher import _process_ptu
    found = _process_ptu(path, irf_path=irf_path, channel=channel)
    return {
        'real': _first_harmonic(found['real_cal']),
        'imag': _first_harmonic(found['imag_cal']),
        'mean': _first_harmonic(found['mean']),
        'frequency': float(found['frequency']),
        'channel': found.get('channel'),
        'calibrated': irf_path is not None,
    }
