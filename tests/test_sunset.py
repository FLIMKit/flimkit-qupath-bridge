import importlib
import sys
import warnings


def _fresh_import():
    for name in list(sys.modules):
        if name.startswith('flimkit_qupath_bridge'):
            del sys.modules[name]
    return importlib.import_module('flimkit_qupath_bridge')


def test_importing_the_shim_warns():
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter('always')
        _fresh_import()

    assert any(issubclass(w.category, DeprecationWarning) for w in caught)


def test_the_warning_names_the_replacement_and_the_removal():
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter('always')
        _fresh_import()

    text = str(caught[0].message)
    assert 'flimkit-bridge' in text
    assert '0.7.0' in text


def test_the_shim_still_works_after_warning():
    module = _fresh_import()

    assert callable(module.server.create_server)
