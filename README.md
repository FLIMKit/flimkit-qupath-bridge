# FLIMKit QuPath bridge

Direct image and ROI exchange between [FLIMKit](https://github.com/FLIMKit/FLIMKit) and [QuPath](https://qupath.github.io/).

## Status

Under development, and private while the design is settled.

It mirrors [flimkit-fiji-bridge](https://github.com/FLIMKit/flimkit-fiji-bridge), which shipped v0.1.0 on 2026-08-15, and reuses its wire protocol unchanged.

## Why QuPath as well as Fiji

The FLIM workflow that matters here is drawing ROIs on a co-registered brightfield image and sending them back to FLIMKit for analysis. QuPath is built for that on tissue, and its annotation handling and built-in cell detection are stronger than Fiji's ROI Manager.

QuPath also treats GeoJSON as a first-class format, so the ROI half of the bridge is less work than it was for Fiji.

## Verified against QuPath 0.7.0

The plan is built on API behaviour confirmed by running it on 2026-08-15, not on documentation alone:

- Headless Groovy scripts run with no image and no project.
- `GsonTools.getInstance().toJson(obj)` emits a GeoJSON `Feature`, and `GsonTools.parseObjectsFromGeoJSON(String)` reads one back.
- Float32 TIFF opens through Bio-Formats with pixel values intact.
- Script arguments arrive as a positional `String[]` named `args`.
- QuPath 0.7.0 runs on Java 25, so there is no Java 8 problem of the kind the Fiji bridge hit.

Two behaviours shape the design. QuPath reads images from a path rather than a stream, so the client writes fetched bytes to a temporary file first. QuPath also normalises polygon winding order, so tests compare geometry rather than an exact coordinate sequence.
