# Jukebox

A media player that runs on your own computer.

- Module id: `jukebox`
- Works on: client (your computer; any server)
- CraftPy Python API: 2.6
- Asks for: reading `pyforge/shared/music`

## What it plays

- **My music**: your files in `pyforge/shared/music` (subfolders too): OGG, WAV, AIFF, MP3 and FLAC. Opus, M4A and
  AAC are not supported; convert those to OGG or MP3 first.
- **Packs**: the music sound events of the game, resource packs and mods (music discs, music packs, sound addons).
- **Melodies**: five public domain note block songs.

## How to use it

1. Put audio files in `pyforge/shared/music` (it is created on the first start).
2. Approve the module once on the **Modules** page (it asks to read that folder, nothing else).
3. Press **J** in game, pick a file. **Refresh** reads the folder again after you add files.

Play / pause, stop, previous / next, a seek bar, volume, loop and shuffle. Closing the window keeps the music
playing; open it again with J to change it. The volume also follows the game's *Jukebox/Note Blocks* slider.

**Nearby players hear it**: the next track you start is sent to the server, which passes it to the players around
you (48 blocks by default; the server can turn it off or change the size limit in `craftpy-common.toml`,
`media.*`). They hear it around you, and can turn other players' media off with `media.hearOthers`.

## How it works

`code/player.py` keeps the play list and the player at module level, so it lives on while the window is closed; the
window shows it once a second (`ui.refresh`). Files play with `media.play(...)`, pack music with `media.play_sound(...)`
and melodies note by note with `client.sound(...)`.
