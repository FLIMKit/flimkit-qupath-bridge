# flimkit-qupath-bridge

Two halves that ship separately: a Python package through pip, and a QuPath
extension as a jar. A user can be running one of each.

## Versioning - always check, without being asked

The version lives in three places and all three have to move together:

- `pyproject.toml`
- `qupath-extension/build.gradle.kts`
- `EXTENSION_VERSION` in `FlimKitBridgeExtension.java`

QuPath compares `EXTENSION_VERSION` against `bridge_version` from
`GET /v1/status` and warns on a mismatch, so leaving the Java constant behind
makes the check itself lie. `tests/test_versions.py` fails when they disagree.

Bump both halves when a change adds a setting or an endpoint, even if only one
side changed, so the warning fires for anyone running the old other half.

Each bridge start mints a new token, so the extension re-reads the discovery
file before every call. A hand-typed address is left alone.

## Code style

Same as FLIMKit for the Python: no comments, no docstrings except a genuinely
complex parameter contract, single quotes including f-string delimiters, keep
f-strings, never align `=`.

## Testing

Run from the repository root with `PYTHONPATH` pointing at the FLIMKit checkout:

```bash
PYTHONPATH=/Users/as-hunt/FLIMKit python -m pytest tests -q
```

Sixty tests skip without real files. `FLIMKIT_TEST_MOSAIC` points at a `.xlif`
or `.lif` mosaic to run those.
