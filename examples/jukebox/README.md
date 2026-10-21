# Jukebox

A media player that runs on your own computer.

- Module id: `jukebox`
- Works on: client (any server)
- CraftPy Python API: 2.9
- Asks for: reading `pyforge/shared/music`, nothing else

## What it plays

- **My music**: your files in `pyforge/shared/music` (subfolders too): OGG, WAV, AIFF, MP3 and FLAC. Opus, M4A and AAC
  are not supported; convert them to OGG or MP3 first.
- **Packs**: the music sound events of the game, resource packs and mods.
- **Melodies**: five public domain note block songs.

## How to use it

1. Put audio files in `pyforge/shared/music` (created on the first start).
2. Approve the module once on the Modules page: turn on Allow requested permissions.
3. Press `J` in game and pick a track. Refresh reads the folder again after you add files.
4. Change the key in Controls or on the Module Keybinds page. The action id `j` is kept so older remaps still work.

The window has play and pause, stop, previous and next, a seek bar, volume, loop and shuffle. Closing the window
keeps the music playing. The volume also follows the game's Jukebox/Note Blocks slider.

**Nearby players hear it**: the next track you start is sent to the server, which passes it to players around you
(48 blocks by default). The server can turn this off or change the limit in `craftpy-common.toml` (`media.*`), and a
player can mute other players' media with `media.hearOthers`.

## How it is made

- `code/main.py` has the `J` key, which refreshes the list and opens the window.
- `code/player.py` keeps the play list and the player at module level, so it lives on while the window is closed.
  The window refreshes once a second. Files play with `media.play(...)`, pack music with `media.play_sound(...)` and
  melodies note by note with `client.sound(...)`. The `jukebox_*` actions are the window's buttons.
- `code/songs.py` holds the melodies as text.
- `ui/jukebox.json` is the window layout. Edit it visually in the UI maker.
- `assets/textures/pocket_jukebox.png` is the icon.
