# FLIMKit QuPath bridge

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21954999.svg)](https://doi.org/10.5281/zenodo.21954999)

Direct image and ROI exchange between [FLIMKit](https://github.com/FLIMKit/FLIMKit) and [QuPath](https://qupath.github.io/).

## Status

v0.1.1. Used end to end against real FLIMKit sessions on a Leica SP8 FALCON: images and ROIs move in both directions, the FLIMKit images open in a QuPath project, and co-registration works through the alignment extension.

The endpoint shapes come from [flimkit-fiji-bridge](https://github.com/FLIMKit/flimkit-fiji-bridge), where they were designed first. The two have since diverged and are not interchangeable: they report different protocol identifiers, and this one adds a discovery file for pairing and refuses requests whose `Host` header is not localhost.

The bigger difference is on the other side. This one ships a QuPath extension that runs inside a live session, so it works with the image you have open and the annotations you have drawn, and it can add the FLIMKit images to the open project. The Fiji bridge drives Fiji through a Groovy script.

## Installing

The Python half goes into the environment FLIMKit runs in:

```bash
pip install flimkit-qupath-bridge
```

That pulls FLIMKit with it. The bridge then starts with FLIMKit, and `flimkit-bridge` is on the path for the headless server.

The QuPath half is a jar. Take `qupath-extension-flimkit-bridge-*.jar` from the release and drop it in QuPath's extensions directory, normally `~/QuPath/v0.7/extensions`. It appears under `Extensions > FLIMKit bridge`.

QuPath can manage that jar instead. In `Extensions > Manage extensions`, add this repository as a catalog:

```
https://github.com/FLIMKit/flimkit-qupath-bridge
```

The bridge then installs from there, and the manager offers each new release.

## Why QuPath as well as Fiji

The FLIM workflow that matters here is drawing ROIs on a co-registered brightfield image and sending them back to FLIMKit for analysis. QuPath is built for that on tissue, and its annotation handling and built-in cell detection are stronger than Fiji's ROI Manager.

QuPath also treats GeoJSON as a first-class format, so the ROI half of the bridge is less work than it was for Fiji.

## Pairing

FLIMKit writes its address and a freshly generated token to `~/.flimkit/qupath-bridge.json` when it starts, owner-readable only where the platform supports it. QuPath reads that file, so `Extensions > FLIMKit bridge > Connect` needs nothing typed in.

If the file names a FLIMKit that is no longer running, QuPath says so rather than failing with a connection error. `Connect to a different address...` is there for the case where the file cannot be reached, such as FLIMKit running in a container or on the other end of an SSH tunnel.

The bridge listens on `127.0.0.1` only and refuses any request whose `Host` header is not localhost, which stops a web page reaching it by pointing its own hostname at your machine. If port 8765 is busy it takes an ephemeral one and records it in the same file, so nothing needs reconfiguring.

## Without the desktop GUI

The bridge does not need the FLIMKit window. `flimkit-bridge` starts the same server on its own, writes the same discovery file, and serves everything except the routes that read the open session:

```bash
flimkit-bridge                 # 127.0.0.1:8765, or an ephemeral port if that is busy
flimkit-bridge --port 9000
flimkit-bridge --no-announce   # do not write the discovery file, print the token instead
```

It refuses to start if another bridge is already serving, since both would write the same discovery file and QuPath would pair with whichever wrote last. Pass `--force` to take it over.

What the desktop bridge adds is the live session: the images and ROIs currently on screen. Everything else, opening files, fitting regions, phasor plots and stitching, works the same either way.

## Stitching and fitting from QuPath

FLIMKit's own tile pipelines are reachable over the bridge, so a mosaic can be stitched and fitted without touching the FLIMKit window. Point it at the `.lif` or `.xlif` that carries the tile positions:

```
POST /v1/pipeline   {"container": "/path/R 2.xlif", "params": {"n_exp": 2}}
GET  /v1/pipeline/defaults
```

The tiles do not have to sit beside the container. The bridge looks in the directory you name, then beside the container, then in the directories next to it, which is where Leica puts them.

`Fit per-pixel lifetimes...` runs the per-pixel fit on the open FLIM image and adds the maps to the project when it finishes, as one image named after the source with a channel each: the intensity in photons, and `tau_mean_amp`, `tau_mean_int` and a `tau_N` per component in nanoseconds. `GET /v1/datasets/{id}/planes/stack.tif?planes=a,b,c` builds it, a float32 OME-TIFF carrying the channel names. They are the values, not a colour render, so QuPath's own display settings do the colouring and a measurement reads back in ns.

The FLIM image keeps one uint16 intensity channel whatever has been fitted. The lifetime channels live on the maps image instead. QuPath persists a server's metadata inside the project entry's builder, so a server that answers with a different channel count the next time it opens fails to load with an index out of bounds, and there is no version of that which works. Keeping the FLIM image uint16 also keeps it openable by the alignment extension.

The acquisition goes onto both entries as well: the histogram window in nanoseconds and how many bins it covers, the laser rate in MHz, the laser period, and the TCSPC resolution in picoseconds. The window and the period are not the same number and both are reported, a 19.505 MHz laser has a 51.269 ns period while its histogram spans 51.297 ns over 529 bins. The rate comes from the file rather than being derived, and is left out rather than guessed when the reader does not carry one.

The fit itself is recorded too. The global summed fit that seeds the per-pixel pass goes into the project entry description and metadata for both the maps and the source image: the lifetimes, the fractions, the reduced chi-squared and which IRF was used. Any annotations on the source image get a mean and a median per fitted map, through `POST /v1/datasets/{id}/planes/stats`, which takes a GeoJSON FeatureCollection and answers per region and per plane. It refuses with 409 before a per-pixel fit has run, and again if the mask and the maps are different shapes because the fit ran at another binning.

`GET /v1/status` reports `bridge_version` and `flimkit_version` alongside the protocol version, and QuPath warns when the extension and the bridge are not the same version. It also re-reads the discovery file before every call, because each bridge start mints a new token, so a restarted bridge used to turn every request into a bare 401 until you hit Connect again. A manually entered address is left alone.

Summed fits run with `workers=1`. `fit_summed` defaults to `workers=-1`, which starts a process pool inside the server for every fit, and on a spawn platform each worker re-imports FLIMKit. It measured slower than a single worker even on a 10 core machine, 1.74s against 1.15s.

Per-pixel fits use the GPU. They were pinned to the CPU so the row banding could report progress, which cost the 4x the GPU gives on a full field. Each band goes to the GPU now, and `use_gpu` turns that off.

When a pipeline job finishes and a QuPath project is open, the maps go into it. The bridge writes a real-unit intensity image beside the run (uint16 when the photon counts fit, float32 otherwise) and points QuPath at that and at the full-range lifetime TIFF, so both carry photons and nanoseconds rather than a display scaling.

The cap on decoding a stack whole is a quarter of the machine's memory, with a 2 GiB floor, so a 1024 square field at 529 bins (2.22 GB) goes through on anything with more than 8 GB and an 8 GB laptop behaves as it did. `FLIMKIT_BRIDGE_MAX_STACK_BYTES` overrides it either way. When a stack is still over the cap, QuPath reads the suggested binning out of the refusal and offers to fit again at it.

`pipeline` chooses between `tile_fit`, the default, which fits each tile after a global summed fit and assembles the maps, and `stitch_fit`, which stitches the raw photons into one canvas and fits that. `tile_fit` is the default because `stitch_fit` writes the whole photon cube to disk before it fits anything: a 124 tile mosaic at 512 square is a 5581 square canvas, which is 57 GB at 459 bins. Both run as jobs, so `GET /v1/jobs/{id}` reports progress and `DELETE` cancels. Cancelling stops the run at the next tile, or at the next stage boundary once fitting is done.

Outputs are written to disk, and the output directory can be reopened as a dataset:

```
POST /v1/datasets   {"path": "/path/R_2_flimkit"}
```

A region drawn on that canvas is then fitted from the photons in the stitched cube, not from the displayed image.

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
- FLIMKit 0.12.0 or newer, which pip pulls in.
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
