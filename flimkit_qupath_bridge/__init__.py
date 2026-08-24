import sys
import warnings

import flimkit_bridge
from flimkit_bridge import (dataset_routes, datasets, discovery, fitting,
                            formats, headless, irf, jobs, phasor, pipeline,
                            server, state, version)

_ALIASES = {
    'dataset_routes': dataset_routes,
    'datasets': datasets,
    'discovery': discovery,
    'fitting': fitting,
    'formats': formats,
    'headless': headless,
    'irf': irf,
    'jobs': jobs,
    'phasor': phasor,
    'pipeline': pipeline,
    'server': server,
    'state': state,
    'version': version,
}

for _name, _module in _ALIASES.items():
    sys.modules[f'{__name__}.{_name}'] = _module

globals().update({
    name: getattr(flimkit_bridge, name)
    for name in dir(flimkit_bridge)
    if not name.startswith('_')
})

warnings.warn(
    'flimkit_qupath_bridge is a compatibility shim and will be removed in '
    '0.7.0. The server it re-exports is flimkit-bridge; import flimkit_bridge '
    'instead. Installing the QuPath extension needs only the jar and '
    'pip install flimkit-bridge.',
    DeprecationWarning,
    stacklevel=2,
)
