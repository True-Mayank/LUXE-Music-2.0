# LUXE Music - Basic Python/Kivy Prototype
# Runs on Windows for testing and is structured to adapt to Android screens.
# Music files stay in the main library when added to or removed from playlists.

import os
import json
import random
from pathlib import Path

from kivy.app import App
from kivy.core.audio import SoundLoader
from kivy.core.window import Window
from kivy.metrics import dp, sp
from kivy.clock import Clock
from kivy.utils import platform
from kivy.lang import Builder
from kivy.properties import StringProperty, BooleanProperty
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.spinner import Spinner
from kivy.uix.widget import Widget
from kivy.graphics import Color, RoundedRectangle, Line

APP_NAME = "LUXE Music"
AUDIO_EXTENSIONS = {".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".opus"}
DATA_DIR = Path.home() / ".luxe_music"
DATA_DIR.mkdir(parents=True, exist_ok=True)
SETTINGS_FILE = DATA_DIR / "settings.json"
PLAYLISTS_FILE = DATA_DIR / "playlists.json"

THEMES = {
    "Deluxe": {"accent": (0.84, 0.70, 0.39, 1), "background": (0.055, 0.055, 0.065, 1), "panel": (0.095, 0.095, 0.11, 1)},
    "Warm": {"accent": (0.84, 0.53, 0.29, 1), "background": (0.075, 0.055, 0.045, 1), "panel": (0.13, 0.09, 0.07, 1)},
    "Sport": {"accent": (0.88, 0.30, 0.34, 1), "background": (0.055, 0.055, 0.07, 1), "panel": (0.095, 0.095, 0.12, 1)},
}
BACKGROUNDS = {
    "Default": (0.055, 0.055, 0.065, 1),
    "Gold": (0.105, 0.075, 0.035, 1),
    "Midnight": (0.025, 0.035, 0.085, 1),
    "Aurora": (0.025, 0.075, 0.085, 1),
}

KV = r"""
#:import dp kivy.metrics.dp
#:import sp kivy.metrics.sp

<RoundButton@Button>:
    background_normal: ''
    background_down: ''
    background_color: app.panel_color
    color: app.text_color
    font_size: '14sp'
    halign: 'center'
    valign: 'middle'
    text_size: self.size
    canvas.before:
        Color:
            rgba: app.panel_color
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(15)]

<LuxeLabel@Label>:
    color: app.text_color
    font_size: '14sp'
    halign: 'left'
    valign: 'middle'
    text_size: self.size

<RootUI>:
    orientation: 'vertical'
    padding: dp(12)
    spacing: dp(10)
    canvas.before:
        Color:
            rgba: app.background_color
        Rectangle:
            pos: self.pos
            size: self.size
    BoxLayout:
        size_hint_y: None
        height: dp(54)
        spacing: dp(8)
        Label:
            text: 'LUXE Music'
            color: app.text_color
            bold: True
            font_size: '24sp'
            halign: 'left'
            valign: 'middle'
            text_size: self.size
        LuxeIconButton:
            icon_name: 'folder'
            size_hint_x: None
            width: dp(48)
            on_release: app.choose_folder()
        LuxeIconButton:
            icon_name: 'refresh'
            size_hint_x: None
            width: dp(48)
            on_release: app.rescan_folder()
    BoxLayout:
        size_hint_y: None
        height: dp(76)
        spacing: dp(8)
        RoundButton:
            text: '♫\\nTrack'
            on_release: app.show_page('Track')
        RoundButton:
            text: '▤\\nPlaylist'
            on_release: app.show_page('Playlist')
        RoundButton:
            text: '♥\\nFavourites'
            on_release: app.show_page('Favourites')
        RoundButton:
            text: '◷\\nRecent'
            on_release: app.show_page('Recent')
    BoxLayout:
        size_hint_y: None
        height: dp(48)
        spacing: dp(8)
        RoundButton:
            text: '▶  ' + app.play_button_text
            size_hint_x: 0.9
            on_release: app.toggle_play()
        Label:
            text: str(len(app.visible_tracks)) + ' track(s)'
            color: app.muted_color
            size_hint_x: 0.7
            font_size: '13sp'
        RoundButton:
            text: 'OPTIONS'
            size_hint_x: None
            width: dp(52)
            on_release: app.open_menu()
    BoxLayout:
        size_hint_y: None
        height: dp(43)
        spacing: dp(8)
        TextInput:
            id: search_box
            hint_text: 'Search songs...'
            multiline: False
            size_hint_x: 1
            background_normal: ''
            background_active: ''
            background_color: app.panel_color
            foreground_color: app.text_color
            hint_text_color: app.muted_color
            padding: [dp(12), dp(12), dp(12), dp(8)]
            on_text: app.apply_search(self.text)
        RoundButton:
            icon_name: 'settings'
            size_hint_x: None
            width: dp(48)
            on_release: app.open_customise()
    Label:
        id: page_title
        text: app.current_page
        size_hint_y: None
        height: dp(28)
        color: app.accent_color
        bold: True
        font_size: '17sp'
        halign: 'left'
        valign: 'middle'
        text_size: self.size
    ScrollView:
        do_scroll_x: False
        GridLayout:
            id: song_list
            cols: 1
            spacing: dp(7)
            padding: [0, dp(2), 0, dp(8)]
            size_hint_y: None
            height: self.minimum_height
    BoxLayout:
        size_hint_y: None
        height: dp(72)
        spacing: dp(10)
        canvas.before:
            Color:
                rgba: app.panel_color
            RoundedRectangle:
                pos: self.pos
                size: self.size
                radius: [dp(18)]
        BoxLayout:
            orientation: 'vertical'
            padding: [dp(12), dp(4)]
            Label:
                text: app.now_playing_title
                color: app.text_color
                bold: True
                font_size: '14sp'
                halign: 'left'
                valign: 'bottom'
                text_size: self.size
            Label:
                text: app.now_playing_subtitle
                color: app.muted_color
                font_size: '12sp'
                halign: 'left'
                valign: 'top'
                text_size: self.size
        RoundButton:
            text: '|◀'
            size_hint_x: None
            width: dp(48)
            on_release: app.play_previous()
        RoundButton:
            text: '▶' if not app.is_playing else 'Ⅱ'
            size_hint_x: None
            width: dp(48)
            on_release: app.toggle_play()
        RoundButton:
            text: '▶|'
            size_hint_x: None
            width: dp(48)
            on_release: app.play_next()
"""

class LuxeIconButton(Button):
    """Small consistent icon button, drawn with simple vector strokes instead of font glyphs."""
    icon_name = StringProperty("folder")

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.text = ""
        self.background_normal = ""
        self.background_down = ""
        self.background_color = (0, 0, 0, 0)
        self.bind(pos=self._draw_icon, size=self._draw_icon)
        self._draw_icon()

    def _draw_icon(self, *_):
        self.canvas.before.clear()
        with self.canvas.before:
            Color(*App.get_running_app().panel_color)
            RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(14)])
        self.canvas.after.clear()
        app = App.get_running_app()
        x, y, w, h = self.x, self.y, self.width, self.height
        cx, cy = x + w/2, y + h/2
        r = min(w, h) * .23
        with self.canvas.after:
            Color(*app.accent_color)
            if self.icon_name == "folder":
                Line(points=[cx-r*1.35,cy-r*.45,cx-r*.25,cy-r*.45,cx+r*.05,cy+r*.05,cx+r*1.35,cy+r*.05,cx+r*1.1,cy-r*.9,cx-r*1.35,cy-r*.9,cx-r*1.35,cy-r*.45], close=False, width=1.5)
            elif self.icon_name == "refresh":
                Line(circle=(cx,cy,r), angle_start=35, angle_end=310, width=1.7)
                Line(points=[cx+r*.55,cy+r*.95,cx+r*1.05,cy+r*.85,cx+r*.95,cy+r*.35], width=1.7)
            elif self.icon_name == "settings":
                Line(circle=(cx,cy,r*.85), width=1.7)
                Line(circle=(cx,cy,r*.3), width=1.7)
                for a in range(0,360,45):
                    import math
                    ax, ay = math.cos(math.radians(a)), math.sin(math.radians(a))
                    Line(points=[cx+ax*r*.95,cy+ay*r*.95,cx+ax*r*1.25,cy+ay*r*1.25], width=1.7)
            elif self.icon_name == "menu":
                for off in (-r*.65,0,r*.65):
                    Line(points=[cx-r,cy+off,cx+r,cy+off], width=1.7)
            elif self.icon_name == "music":
                Line(points=[cx+r*.45,cy+r*.9,cx+r*.45,cy-r*.45,cx-r*.7,cy-r*.2,cx-r*.7,cy-r*.8], width=1.7)
                Line(circle=(cx-r*.95,cy-r*.85,r*.28), width=1.7)
                Line(circle=(cx+r*.2,cy-r*.55,r*.28), width=1.7)

class RootUI(BoxLayout):
    pass


class LuxeMusicApp(App):
    current_page = StringProperty("Track")
    play_button_text = StringProperty("Shuffle")
    now_playing_title = StringProperty("Nothing playing")
    now_playing_subtitle = StringProperty("Choose a folder to load your music")
    is_playing = BooleanProperty(False)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.tracks = []
        self.visible_tracks = []
        self.favourites = set()
        self.recents = []
        self.playlists = {}
        self.active_playlist = None
        self.current_track = None
        self.sound = None
        self.search_text = ""
        self.view_mode = "List"
        self.sort_order = "Ascending"
        self.playing_order = "Consecutively"
        self.theme_name = "Deluxe"
        self.background_name = "Default"
        self.custom_background = ""
        self.font_name = "Roboto"
        self.music_folder = ""
        self.android_tree_uri = ""
        self.android_tracks = {}
        self._android_activity_bound = False
        self.text_color = (0.94, 0.94, 0.96, 1)
        self.muted_color = (0.68, 0.68, 0.72, 1)
        self.accent_color = THEMES["Deluxe"]["accent"]
        self.panel_color = THEMES["Deluxe"]["panel"]
        self.background_color = THEMES["Deluxe"]["background"]
        self._load_data()

    def build(self):
        Window.minimum_width = 340
        Window.minimum_height = 560
        Builder.load_string(KV)
        self.root_ui = RootUI()
        Clock.schedule_once(lambda _dt: self.refresh_tracks(), 0.1)
        return self.root_ui

    def _load_data(self):
        try:
            if SETTINGS_FILE.exists():
                data = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
                self.music_folder = data.get("music_folder", "")
                self.android_tree_uri = data.get("android_tree_uri", "")
                self.theme_name = data.get("theme_name", "Deluxe")
                self.background_name = data.get("background_name", "Default")
                self.custom_background = data.get("custom_background", "")
                self.font_name = data.get("font_name", "Roboto")
                self.view_mode = data.get("view_mode", "List")
                self.sort_order = data.get("sort_order", "Ascending")
                self.playing_order = data.get("playing_order", "Consecutively")
                self.favourites = set(data.get("favourites", []))
                self.recents = data.get("recents", [])
            if PLAYLISTS_FILE.exists():
                self.playlists = json.loads(PLAYLISTS_FILE.read_text(encoding="utf-8"))
        except Exception:
            self.playlists = {}
        self._apply_theme()

    def _save_data(self):
        data = {
            "music_folder": self.music_folder,
            "android_tree_uri": self.android_tree_uri,
            "theme_name": self.theme_name,
            "background_name": self.background_name,
            "custom_background": self.custom_background,
            "font_name": self.font_name,
            "view_mode": self.view_mode,
            "sort_order": self.sort_order,
            "playing_order": self.playing_order,
            "favourites": list(self.favourites),
            "recents": self.recents[-100:],
        }
        SETTINGS_FILE.write_text(json.dumps(data, indent=2), encoding="utf-8")
        PLAYLISTS_FILE.write_text(json.dumps(self.playlists, indent=2), encoding="utf-8")

    def _apply_theme(self):
        theme = THEMES.get(self.theme_name, THEMES["Deluxe"])
        self.accent_color = theme["accent"]
        self.panel_color = theme["panel"]
        self.background_color = BACKGROUNDS.get(self.background_name, theme["background"])
        if self.custom_background and os.path.isfile(self.custom_background):
            # Custom background image support is saved; a later Android-native image picker can provide the path.
            pass

    def show_page(self, page):
        self.current_page = page
        if page == "Playlist":
            self._show_playlist_hub()
        else:
            self.active_playlist = None
            self.refresh_tracks()

    def _show_playlist_hub(self):
        content = BoxLayout(orientation="vertical", spacing=dp(10), padding=dp(12))
        if not self.playlists:
            content.add_widget(Label(text="No playlists yet. Create your first playlist.", color=self.text_color))
        else:
            for name in sorted(self.playlists.keys(), key=str.lower):
                row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(8))
                b = Button(text=f"♫  {name}   ({len(self.playlists[name])})",
                           background_normal="", background_color=self.panel_color, color=self.text_color)
                b.bind(on_release=lambda _b, n=name: self.open_playlist(n))
                row.add_widget(b)
                edit = Button(text="Open", size_hint_x=None, width=dp(70),
                              background_normal="", background_color=self.accent_color, color=(0.05,0.05,0.05,1))
                edit.bind(on_release=lambda _b, n=name: self.open_playlist(n))
                row.add_widget(edit)
                content.add_widget(row)
        actions = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        create = Button(text="+ Create Playlist", background_normal="", background_color=self.accent_color, color=(0.05,0.05,0.05,1))
        create.bind(on_release=lambda *_: self.create_playlist())
        actions.add_widget(create)
        content.add_widget(actions)
        self._replace_song_list(content)

    def _replace_song_list(self, widget):
        if not self.root_ui:
            return
        container = self.root_ui.ids.song_list
        container.clear_widgets()
        container.add_widget(widget)
        widget.size_hint_y = None
        if isinstance(widget, BoxLayout):
            widget.bind(minimum_height=widget.setter("height"))
            widget.height = max(widget.height, dp(100))
        else:
            widget.height = max(widget.height, dp(100))

    def create_playlist(self):
        layout = BoxLayout(orientation="vertical", spacing=dp(10), padding=dp(12))
        name_input = TextInput(hint_text="Playlist name", multiline=False, size_hint_y=None, height=dp(44))
        layout.add_widget(name_input)
        buttons = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        popup = Popup(title="Create playlist", content=layout, size_hint=(0.88, None), height=dp(180), auto_dismiss=True)
        cancel = Button(text="Cancel")
        save = Button(text="Create", background_normal="", background_color=self.accent_color, color=(0.05,0.05,0.05,1))
        buttons.add_widget(cancel); buttons.add_widget(save); layout.add_widget(buttons)
        cancel.bind(on_release=popup.dismiss)
        def save_playlist(*_):
            name = name_input.text.strip()
            if not name:
                self.notify("Enter a playlist name.")
                return
            if name in self.playlists:
                self.notify("A playlist with that name already exists.")
                return
            self.playlists[name] = []
            self._save_data()
            popup.dismiss()
            self._show_playlist_hub()
        save.bind(on_release=save_playlist)
        popup.open()

    def open_playlist(self, name):
        self.active_playlist = name
        self.current_page = "Playlist"
        layout = BoxLayout(orientation="vertical", spacing=dp(8), padding=dp(8))
        header = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(8))
        back = Button(text="← Playlists", size_hint_x=None, width=dp(105))
        back.bind(on_release=lambda *_: self._show_playlist_hub())
        add = Button(text="+ Add songs")
        add.bind(on_release=lambda *_: self.add_songs_to_playlist(name))
        header.add_widget(back); header.add_widget(add); layout.add_widget(header)
        search = TextInput(hint_text="Search in this playlist...", multiline=False, size_hint_y=None, height=dp(40))
        layout.add_widget(search)
        song_scroll = ScrollView()
        song_box = GridLayout(cols=1, spacing=dp(6), size_hint_y=None)
        song_box.bind(minimum_height=song_box.setter("height"))
        song_scroll.add_widget(song_box)
        layout.add_widget(song_scroll)
        def draw_playlist(*_):
            song_box.clear_widgets()
            query = search.text.lower().strip()
            for path in list(self.playlists.get(name, [])):
                if query and query not in self._track_name(path).lower():
                    continue
                row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6))
                play = Button(text="▶  " + Path(self._track_name(path)).stem, halign="left")
                play.bind(on_release=lambda _b, p=path: self.play_path(p))
                remove = Button(text="Remove", size_hint_x=None, width=dp(82))
                remove.bind(on_release=lambda _b, p=path: self.remove_from_playlist(name, p))
                row.add_widget(play); row.add_widget(remove); song_box.add_widget(row)
        search.bind(text=draw_playlist)
        draw_playlist()
        self._replace_song_list(layout)

    def add_songs_to_playlist(self, name):
        if not self.tracks:
            self.notify("Choose or rescan your music folder first.")
            return
        popup_layout = BoxLayout(orientation="vertical", spacing=dp(8), padding=dp(8))
        search = TextInput(hint_text="Search songs to add...", multiline=False, size_hint_y=None, height=dp(40))
        popup_layout.add_widget(search)
        scroll = ScrollView()
        box = GridLayout(cols=1, spacing=dp(5), size_hint_y=None)
        box.bind(minimum_height=box.setter("height"))
        scroll.add_widget(box); popup_layout.add_widget(scroll)
        close = Button(text="Done", size_hint_y=None, height=dp(42))
        popup_layout.add_widget(close)
        popup = Popup(title=f"Add songs to {name}", content=popup_layout, size_hint=(0.94, 0.88))
        def draw(*_):
            box.clear_widgets()
            q = search.text.lower().strip()
            for path in self.tracks:
                if q and q not in Path(self._track_name(path)).stem.lower():
                    continue
                row = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(5))
                row.add_widget(Label(text=Path(self._track_name(path)).stem, color=self.text_color, halign="left", text_size=(dp(210), None)))
                added = path in self.playlists.get(name, [])
                button = Button(text="Added" if added else "Add", size_hint_x=None, width=dp(72),
                                disabled=added, background_normal="", background_color=self.accent_color if not added else self.panel_color,
                                color=(0.05,0.05,0.05,1) if not added else self.muted_color)
                button.bind(on_release=lambda _b, p=path: self._add_path_to_playlist(name, p, popup))
                row.add_widget(button); box.add_widget(row)
        search.bind(text=draw)
        close.bind(on_release=lambda *_: (popup.dismiss(), self.open_playlist(name)))
        draw()
        popup.open()

    def _add_path_to_playlist(self, name, path, popup):
        if path not in self.playlists.setdefault(name, []):
            self.playlists[name].append(path)
            self._save_data()
            popup.dismiss()
            self.add_songs_to_playlist(name)

    def remove_from_playlist(self, name, path):
        if path in self.playlists.get(name, []):
            self.playlists[name].remove(path)
            self._save_data()
            self.open_playlist(name)

    def choose_folder(self):
        if platform == "android":
            self._android_folder_picker()
            return
        start_path = self.music_folder if self.music_folder and os.path.isdir(self.music_folder) else str(Path.home())
        chooser = FileChooserListView(path=start_path, dirselect=True, filters=[])
        layout = BoxLayout(orientation="vertical", spacing=dp(8), padding=dp(10))
        hint = Label(text="Browse to the folder that directly contains your music files. Double-tap folders to open them.",
                     color=self.text_color, size_hint_y=None, height=dp(48), font_size=sp(12), halign="left", valign="middle")
        hint.bind(size=lambda w, *_: setattr(w, "text_size", w.size))
        layout.add_widget(hint)
        path_row = BoxLayout(size_hint_y=None, height=dp(38), spacing=dp(6))
        path_input = TextInput(text=start_path, multiline=False, hint_text="Folder path", size_hint_x=1,
                               background_normal="", background_active="", background_color=self.panel_color,
                               foreground_color=self.text_color, cursor_color=self.accent_color)
        go = Button(text="Go", size_hint_x=None, width=dp(58), background_normal="", background_color=self.accent_color, color=(.05,.05,.05,1))
        path_row.add_widget(path_input); path_row.add_widget(go); layout.add_widget(path_row)
        layout.add_widget(chooser)
        buttons = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        cancel = Button(text="Cancel", background_normal="", background_color=self.panel_color, color=self.text_color)
        select = Button(text="Use this folder", background_normal="", background_color=self.accent_color, color=(.05,.05,.05,1))
        buttons.add_widget(cancel); buttons.add_widget(select); layout.add_widget(buttons)
        popup = Popup(title="Choose your music folder", content=layout, size_hint=(.96, .94), auto_dismiss=False)
        def go_path(*_):
            candidate = os.path.expanduser(path_input.text.strip())
            if os.path.isdir(candidate):
                chooser.path = candidate
                chooser.selection = []
            else:
                self.notify("That folder path does not exist. Browse to a folder or enter a valid path.")
        go.bind(on_release=go_path)
        chooser.bind(path=lambda _w, value: setattr(path_input, "text", value))
        cancel.bind(on_release=popup.dismiss)
        def choose(*_):
            folder = chooser.selection[0] if chooser.selection and os.path.isdir(chooser.selection[0]) else chooser.path
            if folder and os.path.isdir(folder):
                self.music_folder = folder
                self.android_tree_uri = ""
                self._scan_folder()
                self._save_data()
                popup.dismiss()
                self.notify(f"Loaded {len(self.tracks)} music file(s).")
        select.bind(on_release=choose)
        popup.open()

    def _android_folder_picker(self):
        try:
            from jnius import autoclass
            from android import activity
            Intent = autoclass("android.content.Intent")
            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            self._saf_intent_code = 4817
            if not self._android_activity_bound:
                activity.bind(on_activity_result=self._on_android_folder_result)
                self._android_activity_bound = True
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            intent.putExtra("android.content.extra.SHOW_ADVANCED", True)
            PythonActivity.mActivity.startActivityForResult(intent, self._saf_intent_code)
        except Exception as exc:
            self.notify("Android folder picker could not open. Please rebuild the APK with pyjnius enabled. " + str(exc))

    def _on_android_folder_result(self, request_code, result_code, intent):
        if request_code != getattr(self, "_saf_intent_code", -1) or intent is None:
            return
        try:
            from jnius import autoclass
            Intent = autoclass("android.content.Intent")
            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            uri = intent.getData()
            if uri is None:
                return
            flags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            PythonActivity.mActivity.getContentResolver().takePersistableUriPermission(uri, flags)
            self.android_tree_uri = str(uri.toString())
            self.music_folder = ""
            self._scan_android_tree()
            self._save_data()
            self.notify(f"Loaded {len(self.tracks)} music file(s) from selected folder.")
        except Exception as exc:
            self.notify("Could not read that folder. Please select a folder that contains music files. " + str(exc))

    def _scan_android_tree(self):
        """Scan a selected SAF folder using DocumentsContract; keep content URIs for playback."""
        if not self.android_tree_uri:
            return
        try:
            from jnius import autoclass
            Uri = autoclass("android.net.Uri")
            DocumentsContract = autoclass("android.provider.DocumentsContract")
            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            resolver = PythonActivity.mActivity.getContentResolver()
            root_uri = Uri.parse(self.android_tree_uri)
            root_id = DocumentsContract.getTreeDocumentId(root_uri)
            self.tracks = []
            self.android_tracks = {}
            stack = [(root_id, root_uri)]
            while stack:
                parent_id, _parent_uri = stack.pop()
                children_uri = DocumentsContract.buildChildDocumentsUriUsingTree(root_uri, parent_id)
                cursor = resolver.query(children_uri, ["document_id", "display_name", "mime_type"], None, None, None)
                if cursor is None:
                    continue
                try:
                    id_col = cursor.getColumnIndex("document_id")
                    name_col = cursor.getColumnIndex("display_name")
                    mime_col = cursor.getColumnIndex("mime_type")
                    while cursor.moveToNext():
                        doc_id = cursor.getString(id_col)
                        name = str(cursor.getString(name_col) or "Unknown audio")
                        mime = str(cursor.getString(mime_col) or "")
                        child_uri = DocumentsContract.buildDocumentUriUsingTree(root_uri, doc_id)
                        if mime == "vnd.android.document/directory":
                            stack.append((doc_id, child_uri))
                        elif Path(name).suffix.lower() in AUDIO_EXTENSIONS or mime.startswith("audio/"):
                            path = str(child_uri.toString())
                            self.tracks.append(path)
                            self.android_tracks[path] = path
                finally:
                    cursor.close()
            self.tracks.sort(key=lambda p: self._track_name(p).lower())
            self.refresh_tracks()
        except Exception as exc:
            self.notify("Folder access failed: " + str(exc))

    def _track_name(self, path):
        if str(path).startswith("content://"):
            try:
                from jnius import autoclass
                Uri = autoclass("android.net.Uri")
                DocumentsContract = autoclass("android.provider.DocumentsContract")
                uri = Uri.parse(path)
                return str(DocumentsContract.getDocumentId(uri).split(":")[-1]).rsplit("/", 1)[-1]
            except Exception:
                return str(path).rsplit("/", 1)[-1]
        return Path(path).name

    def rescan_folder(self):
        if platform == "android" and self.android_tree_uri:
            self._scan_android_tree()
            self._save_data()
            self.notify(f"Rescanned folder: {len(self.tracks)} track(s) found.")
            return
        if not self.music_folder or not os.path.isdir(self.music_folder):
            self.choose_folder()
            return
        self._scan_folder()
        self._save_data()
        self.notify(f"Rescanned folder: {len(self.tracks)} track(s) found.")

    def _scan_folder(self):
        found = []
        if self.music_folder and os.path.isdir(self.music_folder):
            try:
                for entry in os.scandir(self.music_folder):
                    if entry.is_file() and Path(entry.name).suffix.lower() in AUDIO_EXTENSIONS:
                        found.append(entry.path)
            except OSError:
                pass
        self.tracks = sorted(found, key=lambda p: self._track_name(p).lower())
        self.refresh_tracks()

    def refresh_tracks(self):
        if platform == "android" and self.android_tree_uri and not self.tracks:
            self._scan_android_tree()
            return
        if self.music_folder and os.path.isdir(self.music_folder) and not self.tracks:
            self._scan_folder()
            return
        if self.current_page == "Favourites":
            base = [p for p in self.tracks if p in self.favourites]
        elif self.current_page == "Recent":
            base = [p for p in self.recents if p in self.tracks]
        elif self.current_page == "Playlist" and self.active_playlist:
            base = [p for p in self.playlists.get(self.active_playlist, []) if p in self.tracks]
        else:
            base = list(self.tracks)
        if self.sort_order == "Descending":
            base.sort(key=lambda p: self._track_name(p).lower(), reverse=True)
        else:
            base.sort(key=lambda p: self._track_name(p).lower())
        if self.search_text:
            base = [p for p in base if self.search_text in Path(self._track_name(p)).stem.lower()]
        self.visible_tracks = base
        self._draw_tracks()

    def apply_search(self, text):
        self.search_text = text.lower().strip()
        if self.current_page == "Playlist" and self.active_playlist:
            self.open_playlist(self.active_playlist)
        else:
            self.refresh_tracks()

    def _draw_tracks(self):
        if not self.root_ui:
            return
        container = self.root_ui.ids.song_list
        container.clear_widgets()
        if self.current_page == "Playlist" and not self.active_playlist:
            self._show_playlist_hub()
            return
        if not self.visible_tracks:
            message = "No music found. Tap ▣ to select a folder." if not self.tracks else "No songs match this view."
            container.add_widget(Label(text=message, color=self.muted_color, size_hint_y=None, height=dp(80)))
            return
        if self.view_mode == "Grid":
            container.cols = 2
        else:
            container.cols = 1
        for index, path in enumerate(self.visible_tracks):
            name = Path(self._track_name(path)).stem
            if self.view_mode == "Waterfall":
                height = dp(72 + (index % 3) * 10)
            elif self.view_mode == "Grid":
                height = dp(118)
            else:
                height = dp(60)
            row = BoxLayout(size_hint_y=None, height=height, spacing=dp(8), padding=[dp(7), dp(5)])
            row.canvas.before.clear()
            with row.canvas.before:
                from kivy.graphics import Color, RoundedRectangle
                Color(*self.panel_color)
                RoundedRectangle(pos=row.pos, size=row.size, radius=[dp(13)])
            row.bind(pos=self._update_row_bg, size=self._update_row_bg)
            play = Button(text=("♫\\n" if self.view_mode != "List" else "♫  ") + name,
                          halign="left", valign="middle", text_size=(Window.width * 0.55, None),
                          background_normal="", background_color=(0,0,0,0), color=self.text_color)
            play.bind(on_release=lambda _b, p=path: self.play_path(p))
            row.add_widget(play)
            fav = Button(text="♥" if path in self.favourites else "♡", size_hint_x=None, width=dp(42),
                         background_normal="", background_color=(0,0,0,0),
                         color=self.accent_color if path in self.favourites else self.muted_color)
            fav.bind(on_release=lambda _b, p=path: self.toggle_favourite(p))
            row.add_widget(fav)
            if self.current_page == "Playlist" and self.active_playlist:
                remove = Button(text="−", size_hint_x=None, width=dp(38),
                                background_normal="", background_color=(0,0,0,0), color=self.muted_color)
                remove.bind(on_release=lambda _b, p=path: self.remove_from_playlist(self.active_playlist, p))
                row.add_widget(remove)
            container.add_widget(row)
        container.height = container.minimum_height

    def _update_row_bg(self, widget, _value):
        # Redraw rounded card background when the layout moves/resizes.
        widget.canvas.before.clear()
        from kivy.graphics import Color, RoundedRectangle
        with widget.canvas.before:
            Color(*self.panel_color)
            RoundedRectangle(pos=widget.pos, size=widget.size, radius=[dp(13)])

    def toggle_favourite(self, path):
        if path in self.favourites:
            self.favourites.remove(path)
        else:
            self.favourites.add(path)
        self._save_data()
        self.refresh_tracks()

    def play_path(self, path):
        is_android_uri = platform == "android" and str(path).startswith("content://")
        if not is_android_uri and not os.path.isfile(path):
            self.notify("This song file is no longer available. Rescan the folder.")
            return
        if self.sound:
            try: self.sound.stop()
            except Exception: pass
        if getattr(self, "_native_player", None):
            try: self._native_player.stop(); self._native_player.release()
            except Exception: pass
            self._native_player = None
        self.current_track = path
        self.now_playing_title = Path(self._track_name(path)).stem
        self.now_playing_subtitle = "LUXE Music • Local playback"
        self.recents = [path] + [p for p in self.recents if p != path]
        self.recents = self.recents[:100]
        self._save_data()
        try:
            if is_android_uri:
                from jnius import autoclass
                PythonActivity = autoclass("org.kivy.android.PythonActivity")
                MediaPlayer = autoclass("android.media.MediaPlayer")
                Uri = autoclass("android.net.Uri")
                self.sound = None
                if getattr(self, "_native_player", None):
                    try: self._native_player.stop(); self._native_player.release()
                    except Exception: pass
                player = MediaPlayer()
                player.setDataSource(PythonActivity.mActivity, Uri.parse(path))
                player.setOnCompletionListener(self._android_track_complete)
                player.prepare()
                player.start()
                self._native_player = player
                self.is_playing = True
            else:
                self.sound = SoundLoader.load(path)
                if self.sound:
                    self.sound.play()
                    self.is_playing = True
                else:
                    self.is_playing = False
                    self.notify("This audio format may not be supported by the current Kivy audio provider.")
        except Exception as exc:
            self.is_playing = False
            self.notify("Could not play this file: " + str(exc))
        self.refresh_tracks()

    def _android_track_complete(self, *_):
        Clock.schedule_once(lambda _dt: self.play_next(), 0)

    def toggle_play(self):
        if platform == "android" and getattr(self, "_native_player", None):
            try:
                if self.is_playing:
                    self._native_player.pause()
                    self.is_playing = False
                else:
                    self._native_player.start()
                    self.is_playing = True
            except Exception:
                self.is_playing = False
            return
        if self.sound:
            if self.is_playing:
                self.sound.stop()
                self.is_playing = False
            else:
                self.sound.play()
                self.is_playing = True
        elif self.visible_tracks:
            self.play_path(self.visible_tracks[0])
        else:
            self.notify("Choose a music folder first.")

    def play_next(self):
        if not self.tracks:
            return
        queue = list(self.visible_tracks or self.tracks)
        if self.playing_order == "Randomly":
            candidates = [p for p in queue if p != self.current_track]
            path = random.choice(candidates or queue)
        else:
            try:
                path = queue[(queue.index(self.current_track) + 1) % len(queue)]
            except (ValueError, ZeroDivisionError):
                path = queue[0]
        self.play_path(path)

    def play_previous(self):
        if not self.tracks:
            return
        queue = list(self.visible_tracks or self.tracks)
        try:
            path = queue[(queue.index(self.current_track) - 1) % len(queue)]
        except (ValueError, ZeroDivisionError):
            path = queue[0]
        self.play_path(path)

    def open_menu(self):
        content = BoxLayout(orientation="vertical", spacing=dp(8), padding=dp(12))
        popup = Popup(title="LUXE options", content=content, size_hint=(0.88, None), height=dp(300))
        for title, subtitle, callback in [
            ("▤  View", f"Current: {self.view_mode}", self.open_view_dialog),
            ("☷  Sort Order", f"Current: {self.sort_order}", self.open_sort_dialog),
            ("⇄  Playing Order", f"Current: {self.playing_order}", self.open_playing_dialog),
            ("⚙  Customise LUXE", "Themes, fonts and backgrounds", self.open_customise),
        ]:
            b = Button(text=f"{title}\\n{subtitle}", size_hint_y=None, height=dp(58),
                       background_normal="", background_color=self.panel_color, color=self.text_color)
            b.bind(on_release=lambda _b, cb=callback: (popup.dismiss(), cb()))
            content.add_widget(b)
        close = Button(text="Close", size_hint_y=None, height=dp(40))
        close.bind(on_release=popup.dismiss); content.add_widget(close)
        popup.open()

    def _choice_popup(self, title, choices, current, callback, info=None):
        layout = BoxLayout(orientation="vertical", spacing=dp(10), padding=dp(12))
        if info:
            layout.add_widget(Label(text=info, color=self.muted_color, size_hint_y=None, height=dp(52)))
        spinner = Spinner(text=current, values=choices, size_hint_y=None, height=dp(46))
        layout.add_widget(spinner)
        buttons = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        popup = Popup(title=title, content=layout, size_hint=(0.88, None), height=dp(220 if info else 165))
        cancel = Button(text="Cancel"); apply = Button(text="Apply", background_normal="", background_color=self.accent_color, color=(0.05,0.05,0.05,1))
        buttons.add_widget(cancel); buttons.add_widget(apply); layout.add_widget(buttons)
        cancel.bind(on_release=popup.dismiss)
        def do_apply(*_):
            callback(spinner.text)
            popup.dismiss()
        apply.bind(on_release=do_apply)
        popup.open()

    def open_view_dialog(self):
        self._choice_popup("View", ["List", "Grid", "Waterfall"], self.view_mode,
                           self._set_view, "Choose how your music tracks should appear.")

    def _set_view(self, value):
        self.view_mode = value
        self._save_data()
        self.refresh_tracks()

    def open_sort_dialog(self):
        self._choice_popup("Sort Order", ["Ascending", "Descending"], self.sort_order,
                           self._set_sort, "Sort songs by title in ascending or descending order.")

    def _set_sort(self, value):
        self.sort_order = value
        self._save_data()
        self.refresh_tracks()

    def open_playing_dialog(self):
        self._choice_popup("Playing Order", ["Consecutively", "Randomly"], self.playing_order,
                           self._set_playing_order, "Consecutively follows the list order. Randomly chooses songs in a random order.")

    def _set_playing_order(self, value):
        self.playing_order = value
        self._save_data()

    def open_customise(self):
        layout = BoxLayout(orientation="vertical", spacing=dp(8), padding=dp(12))
        scroll = ScrollView()
        inner = GridLayout(cols=1, spacing=dp(8), size_hint_y=None)
        inner.bind(minimum_height=inner.setter("height"))
        inner.add_widget(Label(text="Accent Theme", color=self.text_color, size_hint_y=None, height=dp(28), halign="left"))
        theme_spinner = Spinner(text=self.theme_name, values=list(THEMES.keys()), size_hint_y=None, height=dp(44))
        inner.add_widget(theme_spinner)
        inner.add_widget(Label(text="Font", color=self.text_color, size_hint_y=None, height=dp(28), halign="left"))
        font_spinner = Spinner(text=self.font_name, values=["Roboto", "Inter", "Arial", "DejaVuSans", "Cool Jazz", "MV Boli"], size_hint_y=None, height=dp(44))
        inner.add_widget(font_spinner)
        inner.add_widget(Label(text="Background", color=self.text_color, size_hint_y=None, height=dp(28), halign="left"))
        bg_grid = GridLayout(cols=2, spacing=dp(8), size_hint_y=None, height=dp(104))
        bg_buttons = {}
        for bg in ["Default", "Gold", "Midnight", "Aurora"]:
            b = Button(text=bg, background_normal="", background_color=BACKGROUNDS[bg], color=(0.95,0.95,0.95,1))
            b.bind(on_release=lambda _b, v=bg: setattr(self, "background_name", v))
            bg_buttons[bg] = b
            bg_grid.add_widget(b)
        inner.add_widget(bg_grid)
        image_button = Button(text="+ Use image from device", size_hint_y=None, height=dp(44))
        image_button.bind(on_release=lambda *_: self.notify("Choosing a custom background image will be connected to the native Android image picker in the APK build."))
        inner.add_widget(image_button)
        scroll.add_widget(inner); layout.add_widget(scroll)
        buttons = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        popup = Popup(title="Customise LUXE", content=layout, size_hint=(0.92, 0.9))
        cancel = Button(text="Cancel"); apply = Button(text="Apply", background_normal="", background_color=self.accent_color, color=(0.05,0.05,0.05,1))
        buttons.add_widget(cancel); buttons.add_widget(apply); layout.add_widget(buttons)
        cancel.bind(on_release=popup.dismiss)
        def apply_settings(*_):
            self.theme_name = theme_spinner.text
            self.font_name = font_spinner.text
            self._apply_theme()
            self._save_data()
            popup.dismiss()
            self.refresh_tracks()
            if self.root_ui:
                self.root_ui.canvas.ask_update()
        apply.bind(on_release=apply_settings)
        popup.open()

    def notify(self, message):
        box = BoxLayout(orientation="vertical", spacing=dp(12), padding=dp(12))
        box.add_widget(Label(text=message, color=self.text_color))
        ok = Button(text="OK", size_hint_y=None, height=dp(42))
        box.add_widget(ok)
        popup = Popup(title="LUXE Music", content=box, size_hint=(0.85, None), height=dp(180))
        ok.bind(on_release=popup.dismiss)
        popup.open()


if __name__ == "__main__":
    LuxeMusicApp().run()
