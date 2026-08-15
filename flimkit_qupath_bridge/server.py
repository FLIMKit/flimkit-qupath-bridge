import json
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO

import numpy as np
import tifffile


def _empty_collection():
    return {'type': 'FeatureCollection', 'features': []}


@dataclass
class BridgeState:
    images: dict[str, np.ndarray]
    units: dict[str, str] = field(default_factory=dict)
    exported_rois: dict = field(default_factory=_empty_collection)
    received_rois: list[dict] = field(default_factory=list)
    app: object = None
    connected: bool = False


def _live_images(state):
    from flimkit.plugins import get_current_images
    current = get_current_images(state.app)
    return current.get('images', {}), current.get('units', {})


def _live_rois(state):
    from flimkit.plugins import export_rois_geojson
    return export_rois_geojson(state.app)


def _live_import(state, payload):
    from flimkit.plugins import import_rois_geojson
    return import_rois_geojson(state.app, payload, mode='append')


_ALLOWED_HOSTS = ('127.0.0.1', 'localhost', '::1', '[::1]')


def _host_allowed(header):
    if not header:
        return True
    name = header.rsplit(':', 1)[0] if header.count(':') == 1 else header
    return name.strip().lower() in _ALLOWED_HOSTS


def create_server(host: str, port: int, token: str, state: BridgeState,
                  live: bool = False):
    class Handler(BaseHTTPRequestHandler):
        def _local_only(self):
            if _host_allowed(self.headers.get('Host')):
                return True
            self.send_error(403, 'the bridge only answers to localhost')
            return False

        def _authorized(self):
            ok = self.headers.get('Authorization') == f'Bearer {token}'
            if ok:
                state.connected = True
            return ok

        def _send_json(self, status, payload):
            body = json.dumps(payload).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self._local_only():
                return
            if self.path == '/v1/status':
                self._send_json(200, {
                    'protocol': 'flimkit-qupath',
                    'protocol_version': 1,
                })
                return
            if self.path == '/v1/rois':
                if not self._authorized():
                    self.send_error(401)
                    return
                try:
                    payload = _live_rois(state) if live else state.exported_rois
                except Exception as exc:
                    self.send_error(500, str(exc))
                    return
                self._send_json(200, payload)
                return
            prefix = '/v1/images/'
            if self.path.startswith(prefix) and self.path.endswith('.tif'):
                if not self._authorized():
                    self.send_error(401)
                    return
                image_id = self.path[len(prefix):-len('.tif')]
                images, units = state.images, state.units
                if live:
                    try:
                        images, units = _live_images(state)
                    except Exception as exc:
                        self.send_error(500, str(exc))
                        return
                try:
                    image = images[image_id]
                except KeyError:
                    self.send_error(404)
                    return
                buffer = BytesIO()
                tifffile.imwrite(buffer, np.asarray(image, dtype=np.float32))
                body = buffer.getvalue()
                self.send_response(200)
                self.send_header('Content-Type', 'image/tiff')
                self.send_header('Content-Length', str(len(body)))
                self.send_header(
                    'X-FLIMKit-Value-Unit', units.get(image_id, ''))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_error(404)

        def do_POST(self):
            if not self._local_only():
                return
            if self.path != '/v1/rois':
                self.send_error(404)
                return
            if not self._authorized():
                self.send_error(401)
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                payload = json.loads(self.rfile.read(length).decode('utf-8'))
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                self.send_error(400)
                return
            if payload.get('type') != 'FeatureCollection':
                self.send_error(400)
                return
            state.received_rois.append(payload)
            if live:
                try:
                    _live_import(state, payload)
                except Exception as exc:
                    self.send_error(500, str(exc))
                    return
            self._send_json(200, {
                'received_features': len(payload.get('features', [])),
            })

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer((host, port), Handler)
    server.state = state
    return server
