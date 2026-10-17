# The player: what plays, the play list and the window. Everything lives at module level (not in the window), so the
# music keeps going while the window is closed; the window only shows it and sends the clicks.
#
# Three sources:
#   My music   files of pyforge/shared/music (OGG, WAV, AIFF, MP3, FLAC), played by the game's sound engine
#   Packs      music sound events of the game, resource packs and mods (music discs, music packs)
#   Melodies   the note block songs of songs.py
import random

from craftpy import *
from songs import TRACKS, INSTRUMENTS

SOURCES = ("My music", "Packs", "Melodies")
FOLDER = "music"


class Player:
    def __init__(self):
        self.source = 0          # index in SOURCES
        self.lists = {0: [], 1: [], 2: [t[0] for t in TRACKS]}
        self.index = -1          # what plays in the list of self.playing_source
        self.playing_source = 0
        self.loop = True
        self.shuffle = False
        self.nearby = False
        self.volume = 80         # percent
        self.instrument = 0
        self.message = ""
        self.melody = None       # (position, task) while a melody plays
        self.state = {"state": "stopped", "position": 0, "duration": -1, "name": ""}

    # ---- lists

    def refresh(self):
        """Reads the folder and the pack music again (the Refresh button)."""
        try:
            self.lists[0] = media.files(FOLDER)
            self.message = "" if self.lists[0] else "Put audio files in pyforge/shared/music, then press Refresh"
        except PermissionError as e:
            self.lists[0] = []
            self.message = str(e)
        try:
            self.lists[1] = media.sounds()
        except PermissionError:
            self.lists[1] = []

    # ---- playing

    def play(self, source, index):
        self.stop()
        items = self.lists[source]
        if not items:
            return
        self.playing_source, self.index = source, index % len(items)
        name = items[self.index]
        try:
            if source == 0:
                media.play(name, folder=FOLDER, volume=self.volume / 100, nearby=self.nearby)
            elif source == 1:
                media.play_sound(name, volume=self.volume / 100)
            else:
                self.melody = [0, after(0, self.step)]
            self.message = ""
        except PermissionError as e:
            self.message = str(e)

    def stop(self):
        if self.melody:
            self.melody[1].cancel()
            self.melody = None
        media.stop()

    def toggle(self):
        if self.melody:
            self.stop()
        elif self.state["state"] == "playing":
            media.pause()
        elif self.state["state"] == "paused":
            media.resume()
        else:
            self.play(self.source, max(self.index, 0))

    def skip(self, step):
        items = self.lists[self.playing_source]
        if not items:
            return
        if self.shuffle and step > 0:
            self.play(self.playing_source, random.randrange(len(items)))
        elif step < 0 and self.state.get("position", 0) > 3:
            self.play(self.playing_source, self.index)  # like a real player: back to the start first
        else:
            self.play(self.playing_source, self.index + step)

    def step(self):
        """One note of a melody (client.sound plays it only here), then the next one."""
        if not self.melody:
            return
        title, bpm, notes = TRACKS[self.index]
        position = self.melody[0]
        if position >= len(notes):
            self.melody = None
            self.finished()
            return
        pitch, beats = notes[position]
        if pitch is not None:
            client.sound("minecraft:block.note_block." + INSTRUMENTS[self.instrument], volume=self.volume / 100, pitch=pitch)
        self.melody = [position + 1, after(beats * 60 / bpm, self.step)]

    def finished(self):
        last = self.index + 1 >= len(self.lists[self.playing_source])
        if self.shuffle or not last or self.loop:
            self.skip(1)

    def tick(self):
        """Once a second: the state for the window, and the next track when one ends."""
        self.state = media.state()
        if self.melody:
            title, bpm, notes = TRACKS[self.index]
            self.state = {"state": "playing", "name": title, "position": self.melody[0], "duration": len(notes)}
        elif self.state["state"] == "ended":
            self.finished()


PLAYER = Player()


def clock(seconds):
    if seconds is None or seconds < 0:
        return "--:--"
    return f"{int(seconds // 60)}:{int(seconds % 60):02d}"


@window("jukebox", title="♪ CraftPy Jukebox", size=(420, 470))
class Jukebox(Window):
    def build(self, ctx):
        p = PLAYER
        s = p.state
        playing = s["state"] == "playing"
        melody = p.melody is not None
        duration = s.get("duration", -1)
        where = f"note {s['position']}/{duration}" if melody else f"{clock(s.get('position'))} / {clock(duration)}"
        items = p.lists[p.source]
        marks = [("▶ " if p.source == p.playing_source and i == p.index and s["state"] != "stopped" else "   ") + name
                 for i, name in enumerate(items)]
        return VBox(
            Label("♪ " + (s.get("name") or "Nothing playing"), style="title"),
            Label(f"{s['state']}  ·  {where}" + ("  ·  heard nearby" if s.get("shared") else "")),
            Slider(value=int(s.get("position") or 0), min=0, max=max(1, int(duration if duration > 0 else 1)),
                   on_change=self.seek) if not melody and duration > 0 else ProgressBar(value=s["position"], max=max(1, duration)),
            HBox(
                Button("⏮", on_click=self.previous),
                Button("⏸ Pause" if playing and not melody else ("⏹ Stop" if melody else "▶ Play"), on_click=self.toggle),
                Button("⏹", on_click=self.stop),
                Button("⏭", on_click=self.next),
                Button("⟳ Refresh", on_click=self.refresh, style="text"),
                spacing=4,
            ),
            HBox(Label(f"Volume {p.volume}%"), Slider(value=p.volume, min=0, max=100, on_change=self.set_volume), spacing=8),
            HBox(Checkbox("Loop", checked=p.loop, on_change=self.set_loop),
                 Checkbox("Shuffle", checked=p.shuffle, on_change=self.set_shuffle),
                 Checkbox("Nearby players hear it", checked=p.nearby, on_change=self.set_nearby), spacing=8),
            HBox(Label("Melody instrument"), Dropdown([i.replace("_", " ") for i in INSTRUMENTS], selected=p.instrument,
                                                      on_change=self.set_instrument), spacing=8),
            HBox(Label("Source"), Dropdown(list(SOURCES), selected=p.source, on_change=self.pick_source), spacing=8),
            ListView(marks or ["(empty)"], on_select=self.pick),
            Label(p.message or "Closing the window keeps the music playing.", style="hint"),
            spacing=6, padding=6,
        )

    # ---- clicks (each one redraws the window)

    def pick(self, ctx):
        PLAYER.play(PLAYER.source, int(ctx.value))
        PLAYER.tick()

    def pick_source(self, ctx):
        PLAYER.source = int(ctx.value)

    def toggle(self, ctx):
        PLAYER.toggle()
        PLAYER.tick()

    def stop(self, ctx):
        PLAYER.stop()
        PLAYER.tick()

    def next(self, ctx):
        PLAYER.skip(1)
        PLAYER.tick()

    def previous(self, ctx):
        PLAYER.skip(-1)
        PLAYER.tick()

    def refresh(self, ctx):
        PLAYER.refresh()

    def seek(self, ctx):
        media.seek(float(ctx.value))
        PLAYER.tick()

    def set_volume(self, ctx):
        PLAYER.volume = int(ctx.value)
        media.volume(PLAYER.volume / 100)

    def set_loop(self, ctx):
        PLAYER.loop = bool(ctx.value)

    def set_shuffle(self, ctx):
        PLAYER.shuffle = bool(ctx.value)

    def set_nearby(self, ctx):
        PLAYER.nearby = bool(ctx.value)  # from the next track on

    def set_instrument(self, ctx):
        PLAYER.instrument = int(ctx.value)


@every(seconds=1)
def follow():
    PLAYER.tick()
    me = client.player()
    if me is not None:
        ui.refresh(me.name, "jukebox")  # False when the window is closed: the music plays on anyway
