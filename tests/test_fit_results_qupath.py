import json
import os
import subprocess
from pathlib import Path

import pytest

QUPATH_PATH = os.environ.get('QUPATH_PATH', '')
EXTENSION_JARS = sorted(
    (Path.home() / 'QuPath' / 'v0.7' / 'extensions').glob(
        'qupath-extension-flimkit-bridge-*.jar'))

pytestmark = pytest.mark.skipif(
    not QUPATH_PATH or not EXTENSION_JARS,
    reason='needs QUPATH_PATH and the extension installed',
)


def _apply(payload, tmp_path):
    target = tmp_path / 'results.json'
    target.write_text(json.dumps(payload))
    script = Path(__file__).parents[1] / 'qupath' / 'ApplyFitResults.groovy'
    completed = subprocess.run(
        [QUPATH_PATH, 'script', str(script), '--args', str(target)],
        capture_output=True, text=True, timeout=600)
    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output
    assert 'APPLY_DONE' in output, output
    parsed = {}
    for line in output.splitlines():
        if line.startswith('OBJECT '):
            fields = dict(f.split('=', 1)
                          for f in line.split('OBJECT ', 1)[1].split('|') if '=' in f)
            parsed[fields['name']] = fields
        elif line.startswith('APPLIED '):
            parsed['_applied'] = line.split('count=')[1].strip()
        elif line.startswith('ERRORS '):
            parsed['_errors'] = line.split('ERRORS ', 1)[1].strip()
    return parsed, output


def test_measurements_land_on_the_annotations(tmp_path):
    payload = {'results': [
        {'name': 'Cell 1', 'tau_mean_ns': 2.34, 'chi2_r': 1.08,
         'taus_ns': [1.1, 3.3], 'fractions': [0.4, 0.6],
         'photon_count': 5000, 'n_pixels': 120, 'n_exp': 2,
         'tau_mean_amp_ns': 2.3, 'tau_mean_int_ns': 2.6, 'chi2_r_tail': 0.99},
        {'name': 'Cell 2', 'tau_mean_ns': 1.11, 'chi2_r': 1.44,
         'taus_ns': [1.11], 'fractions': [1.0],
         'photon_count': 900, 'n_pixels': 30, 'n_exp': 1,
         'tau_mean_amp_ns': 1.1, 'tau_mean_int_ns': 1.1, 'chi2_r_tail': 1.2},
    ]}

    parsed, output = _apply(payload, tmp_path)

    assert parsed['_applied'] == '2'
    assert float(parsed['Cell 1']['tau_mean']) == pytest.approx(2.34)
    assert float(parsed['Cell 1']['chi2r']) == pytest.approx(1.08)
    assert float(parsed['Cell 1']['tau1']) == pytest.approx(1.1)
    assert float(parsed['Cell 2']['tau_mean']) == pytest.approx(1.11)
    assert int(parsed['Cell 1']['n']) >= 10


def test_a_failed_region_is_reported_not_applied(tmp_path):
    payload = {'results': [
        {'name': 'Cell 1', 'error': 'the region selects no pixels'},
        {'name': 'Cell 2', 'tau_mean_ns': 1.5, 'chi2_r': 1.0,
         'taus_ns': [1.5], 'fractions': [1.0], 'photon_count': 10,
         'n_pixels': 2, 'n_exp': 1, 'tau_mean_amp_ns': 1.5,
         'tau_mean_int_ns': 1.5, 'chi2_r_tail': 1.0},
    ]}

    parsed, _ = _apply(payload, tmp_path)

    assert parsed['_applied'] == '1'
    assert 'selects no pixels' in parsed['_errors']
    assert parsed['Cell 1']['n'] == '0'
