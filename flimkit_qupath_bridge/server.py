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


def create_server(host: str, port: int, token: str, state: BridgeState):
    class Handler(BaseHTTPRequestHandler):
        def _authorized(self):
            return self.headers.get('Authorization') == f'Bearer {token}'

        def _send_json(self, status, payload):
            body = json.dumps(payload).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
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
                self._send_json(200, state.exported_rois)
                return
            prefix = '/v1/images/'
            if self.path.startswith(prefix) and self.path.endswith('.tif'):
                if not self._authorized():
                    self.send_error(401)
                    return
                image_id = self.path[len(prefix):-len('.tif')]
                try:
                    image = state.images[image_id]
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
                    'X-FLIMKit-Value-Unit', state.units.get(image_id, ''))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_error(404)

        def do_POST(self):
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
            self._send_json(200, {
                'received_features': len(payload.get('features', [])),
            })

        def log_message(self, format, *args):
            pass

    return ThreadingHTTPServer((host, port), Handler)
