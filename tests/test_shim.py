def test_the_old_module_paths_still_resolve():
    from flimkit_qupath_bridge import server
    from flimkit_qupath_bridge import phasor
    from flimkit_qupath_bridge import dataset_routes

    assert callable(server.create_server)
    assert callable(phasor.compute)
    assert callable(dataset_routes.phasor_summary)


def test_the_shim_is_the_shared_package():
    from flimkit_bridge import phasor as shared
    from flimkit_qupath_bridge import phasor as shimmed

    assert shimmed is shared


def test_the_version_reported_is_the_servers():
    from flimkit_qupath_bridge.version import report
    from flimkit_bridge.version import report as shared

    assert report() == shared()


def test_importing_the_dotted_path_works_too():
    import flimkit_qupath_bridge.datasets

    assert hasattr(flimkit_qupath_bridge.datasets, 'DatasetRegistry')
