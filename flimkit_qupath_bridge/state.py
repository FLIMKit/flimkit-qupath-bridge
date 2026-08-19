from flimkit_qupath_bridge.datasets import DatasetRegistry
from flimkit_qupath_bridge.jobs import JobRegistry
from flimkit_qupath_bridge.server import BridgeState


def build_state(datasets=None, jobs=None):
    state = BridgeState(images={}, units={})
    state.datasets = datasets if datasets is not None else DatasetRegistry()
    state.jobs = jobs if jobs is not None else JobRegistry()
    return state
