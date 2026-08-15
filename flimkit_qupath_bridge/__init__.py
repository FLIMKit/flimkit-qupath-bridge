from flimkit.plugins import plugin_config, tool

FLIMKIT_PLUGIN_API = 1

PLUGIN_NAME = 'qupath_bridge'


@tool(id='qupath_bridge_open', label='QuPath Bridge...', menu='Tools', order=510)
def open_bridge(app):
    from tkinter import messagebox
    cfg = plugin_config(PLUGIN_NAME)
    opened = int(cfg.get('times_opened', 0) or 0) + 1
    cfg.set('times_opened', opened)
    cfg.save()
    messagebox.showinfo(
        'QuPath Bridge',
        'The QuPath bridge add-on is installed.\n\n'
        'Image and ROI exchange is still under development. See the project '
        'README for the current test instructions.\n\n'
        f'Opened {opened} time(s).',
        parent=app.root,
    )
