# Jukebox - a media player that runs on YOUR computer (a client-side module).
#
#   player.py   the play list, playing, and the window (it keeps playing when the window is closed)
#   songs.py    note block melodies as text, a third source next to your files and the packs' music
#
# Your own music: put OGG, WAV, AIFF, MP3 or FLAC files in pyforge/shared/music (subfolders work), open the jukebox
# with J and press Refresh. The module asks to read that folder ("permissions": {"shared": ["music"]} in project.json);
# approve it once on the Modules page. "Nearby players hear it" sends the file to the server, which passes it to the
# players around you (when the server allows it).
from craftpy import *

import player


# Preserve the old implicit ID even if a future version changes the default shortcut.
@keys.bind("j", id="j", name="Open Jukebox", category="Jukebox")
def open_jukebox(ctx):
    player.PLAYER.refresh()
    client.open_window("jukebox")


def main(ctx):
    player.PLAYER.refresh()
    ctx.log("Jukebox ready on this computer: press J. Music folder: pyforge/shared/music")
