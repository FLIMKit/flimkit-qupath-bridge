# FLIMKit QuPath bridge

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21954999.svg)](https://doi.org/10.5281/zenodo.21954999)

Direct image and ROI exchange between [FLIMKit](https://github.com/FLIMKit/FLIMKit) and [QuPath](https://qupath.github.io/).

## Status

v0.1.1. Used end to end against real FLIMKit sessions on a Leica SP8 FALCON: images and ROIs move in both directions, the FLIMKit images open in a QuPath project, and co-registration works through the alignment extension.

The endpoint shapes come from [flimkit-fiji-bridge](https://github.com/FLIMKit/flimkit-fiji-bridge), where they were designed first. The two have since diverged and are not interchangeable: they report different protocol identifiers, and this one adds a discovery file for pairing and refuses requests whose `Host` header is not localhost.

The bigger difference is on the other side. This one ships a QuPath extension that runs inside a live session, so it works with the image you have open and the annotations you have drawn, and it can add the FLIMKit images to the open project. The Fiji bridge drives Fiji through a Groovy script.

## Why QuPath as well as Fiji

The FLIM workflow that matters here is drawing ROIs on a co-registered brightfield image and sending them back to FLIMKit for analysis. QuPath is built for that on tissue, and its annotation handling and built-in cell detection are stronger than Fiji's ROI Manager.

QuPath also treats GeoJSON as a first-class format, so the ROI half of the bridge is less work than it was for Fiji.

## Pairing

FLIMKit writes its address and a freshly generated token to `~/.flimkit/qupath-bridge.json` when it starts, owner-readable only where the platform supports it. QuPath reads that file, so `Extensions > FLIMKit bridge > Connect` needs nothing typed in.

If the file names a FLIMKit that is no longer running, QuPath says so rather than failing with a connection error. `Connect to a different address...` is there for the case where the file cannot be reached, such as FLIMKit running in a container or on the other end of an SSH tunnel.

The bridge listens on `127.0.0.1` only and refuses any request whose `Host` header is not localhost, which stops a web page reaching it by pointing its own hostname at your machine. If port 8765 is busy it takes an ephemeral one and records it in the same file, so nothing needs reconfiguring.

## Acknowledgement

The wire protocol used here was designed and first implemented in
[flimkit-fiji-bridge](https://github.com/FLIMKit/flimkit-fiji-bridge) by Zhen Yuan Yeo
(https://doi.org/10.5281/zenodo.21951612). This bridge reuses it unchanged, so a
client written against one works against the other.

## Licensing

Two licences, because the two halves link against different things. The Python
package is MIT. The QuPath extension is GPL-3.0, because it links QuPath, which is
GPL-3.0. See [LICENSING.md](LICENSING.md).

## Requirements

- QuPath 0.7.0 or newer.
- FLIMKit with the plugin bindings, which means a build after PR #52.
- Python 3.12 or newer.
- The [QuPath alignment extension](https://github.com/qupath/qupath-extension-align), for co-registration.

Align on the intensity image, not the lifetime map. Photon counts are integers, so intensity crosses as 16-bit whenever it fits losslessly, which the alignment extension can open. The lifetime map has to stay 32-bit float to carry real nanoseconds, and the alignment extension throws on 32-bit float rather than declining politely. The transform you get from the intensity image is valid for the lifetime map anyway, because they share one pixel grid.

The alignment extension is not optional for the intended workflow and it does not ship with QuPath. QuPath 0.7.0 does not bundle interactive image alignment, and neither did 0.6.0, so it has to be downloaded and dropped into QuPath's extensions directory separately.

Without it you can still move images and ROIs, but only between images that already share a coordinate system. Aligning a brightfield or histology image to the FLIM field of view, which is the reason this bridge exists, needs that extension installed.

## Co-registration

FLIMKit receives ROIs in FLIM image-pixel coordinates. Anything drawn on another image has to be transformed into that space before it is sent, and the transform is produced on the QuPath side, the same division of labour the Fiji bridge uses.

The intended sequence:

1. Open the brightfield image and add the FLIM intensity map to the same QuPath project.
2. Align them with the alignment extension and transfer the annotations onto the FLIM image.
3. Send the annotations on the FLIM image to FLIMKit.

This bridge deliberately contains no alignment code of its own. Reimplementing it would mean maintaining a copy of something QuPath's own developers already maintain.

## Verified against QuPath 0.7.0

The plan is built on API behaviour confirmed by running it on 2026-08-15, not on documentation alone:

- Headless Groovy scripts run with no image and no project.
- `GsonTools.getInstance().toJson(obj)` emits a GeoJSON `Feature`, and `GsonTools.parseObjectsFromGeoJSON(String)` reads one back.
- Float32 TIFF opens through Bio-Formats with pixel values intact.
- Script arguments arrive as a positional `String[]` named `args`.
- QuPath 0.7.0 runs on Java 25, so there is no Java 8 problem of the kind the Fiji bridge hit.

Two behaviours shape the design. QuPath reads images from a path rather than a stream, so the client writes fetched bytes to a temporary file first. QuPath also normalises polygon winding order, so tests compare geometry rather than an exact coordinate sequence.
