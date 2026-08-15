# Licensing

This repository holds two pieces of software under two different licences.

## `flimkit_qupath_bridge/` and `tests/` are MIT

The Python package is licensed under the MIT License in `LICENSE.md`. It talks to
QuPath over HTTP and does not link against any QuPath code, so it carries the same
licence as the rest of FLIMKit.

## `qupath-extension/` is GPL-3.0

The QuPath extension is licensed under the GNU General Public License v3.0 in
`qupath-extension/LICENSE.txt`. It imports and links against QuPath, which is
GPL-3.0, so the extension is distributed under the same terms.

This is the usual arrangement for QuPath extensions rather than a choice specific
to this project.

## What this means in practice

Anything built on the Python bridge, including a bridge to a different image
analysis program, can be MIT. Anything built on the QuPath extension inherits
GPL-3.0.

The wire protocol itself is not covered by either licence. It is documented in the
README and reused unchanged from
[flimkit-fiji-bridge](https://github.com/FLIMKit/flimkit-fiji-bridge).
