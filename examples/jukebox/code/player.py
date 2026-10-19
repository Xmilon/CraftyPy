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
        self.loop = storage.get("jukebox_loop", True)
        self.shuffle = storage.get("jukebox_shuffle", False)
        self.nearby = storage.get("jukebox_nearby", False)
        self.volume = storage.get("jukebox_volume", 80)         # percent
        self.instrument = storage.get("jukebox_instrument", 0)
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


@ui.data("jukebox")
def player_data(ctx):
    p, s = PLAYER, PLAYER.state
    melody = p.melody is not None
    duration = s.get("duration", -1)
    position = max(0, int(s.get("position") or 0))
    maximum = max(1, int(duration if duration > 0 else 1))
    where = f"note {position}/{duration}" if melody else f"{clock(position)} / {clock(duration)}"
    tracks = p.lists[p.source]
    marks = [("▶ " if p.source == p.playing_source and i == p.index and s["state"] != "stopped" else "   ") + name
             for i, name in enumerate(tracks[:1024])]
    return dict(track_title="♪ " + (s.get("name") or "Nothing playing"),
                state_caption=f"{s['state']} · {where}" + (" · heard nearby" if s.get("shared") else ""),
                position=position, duration=maximum, seek_visible=not melody and duration > 0,
                progress_visible=melody or duration <= 0,
                toggle_label="⏹ Stop" if melody else "⏸ Pause" if s["state"] == "playing" else "▶ Play",
                volume=p.volume, volume_label=f"Volume {p.volume}%", loop=p.loop, shuffle=p.shuffle,
                nearby=p.nearby, instruments=[i.replace("_", " ") for i in INSTRUMENTS], instrument=p.instrument,
                sources=list(SOURCES), source=p.source, tracks=marks, has_tracks=bool(tracks),
                selected_track=p.index if p.source == p.playing_source and p.index < len(marks) else -1,
                message=p.message or ("Showing the first 1024 tracks." if len(tracks) > 1024 else "Closing the window keeps the music playing."))


def _index(value, length):
    try:
        value = int(value)
        return value if 0 <= value < length else None
    except (TypeError, ValueError):
        return None


@action("jukebox_pick")
def pick(ctx):
    index = _index(ctx.value, min(1024, len(PLAYER.lists[PLAYER.source])))
    if index is not None:
        PLAYER.play(PLAYER.source, index)
        PLAYER.tick()


@action("jukebox_source")
def pick_source(ctx):
    index = _index(ctx.value, len(SOURCES))
    if index is not None:
        PLAYER.source = index


@action("jukebox_toggle")
def toggle(ctx):
    PLAYER.toggle()
    PLAYER.tick()


@action("jukebox_stop")
def stop(ctx):
    PLAYER.stop()
    PLAYER.tick()


@action("jukebox_next")
def next_track(ctx):
    PLAYER.skip(1)
    PLAYER.tick()


@action("jukebox_previous")
def previous(ctx):
    PLAYER.skip(-1)
    PLAYER.tick()


@action("jukebox_refresh")
def refresh(ctx):
    PLAYER.refresh()


@action("jukebox_seek")
def seek(ctx):
    duration = PLAYER.state.get("duration", -1)
    if not PLAYER.melody and duration > 0:
        media.seek(max(0, min(duration, float(ctx.value))))
        PLAYER.tick()


@action("jukebox_volume")
def set_volume(ctx):
    value = _index(ctx.value, 101)
    if value is not None:
        PLAYER.volume = value
        storage.set("jukebox_volume", value)
        media.volume(value / 100)


@action("jukebox_loop")
def set_loop(ctx):
    PLAYER.loop = ctx.value is True
    storage.set("jukebox_loop", PLAYER.loop)


@action("jukebox_shuffle")
def set_shuffle(ctx):
    PLAYER.shuffle = ctx.value is True
    storage.set("jukebox_shuffle", PLAYER.shuffle)


@action("jukebox_nearby")
def set_nearby(ctx):
    PLAYER.nearby = ctx.value is True
    storage.set("jukebox_nearby", PLAYER.nearby)


@action("jukebox_instrument")
def set_instrument(ctx):
    value = _index(ctx.value, len(INSTRUMENTS))
    if value is not None:
        PLAYER.instrument = value
        storage.set("jukebox_instrument", value)


@every(seconds=1)
def follow():
    PLAYER.tick()
    me = client.player()
    if me is not None:
        ui.refresh(me.name, "jukebox")  # False when the window is closed: the music plays on anyway
