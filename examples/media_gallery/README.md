# Media Gallery

An ordinary window with pictures, sound and video in it: an animated GIF as the background, a video with a control bar,
two sound effects on buttons, and a slider and a checkbox that change them. It shows that media are widgets like any other,
not a separate player.

- Module id: `media_gallery`
- Works on: client and server (the window is opened by the server; the media travel in the module's resource pack)
- CraftPy Python API: 2.10
- Asks for: no permissions

## How to use it

Open the Hub, then Apps, then Media Gallery. Or run `/media_gallery:open`.

- Press play on the clip (or use the bar: seek, speed 0.5x to 2x, mute, volume). When it ends the counter at the bottom goes up.
- **Click** and **Chime** play a sound each time they are pressed, over the clip.
- **Mute everything** and the **Volume** slider change the clip and both effects at once.

## How it is made

- `assets/media/` holds the files: `background.gif` (an animated GIF), `clip.avi` (video as Motion JPEG), `clip.wav` (the sound
  of the video, a separate file) and `click.wav`, `chime.wav`. Only files under `assets/media/` can be used.
- `ui/gallery.json` is the window, drawn in the UI maker. The background is an `image` at the back of a free layout (`z: -1`,
  `opacity: 0.4`, `fit: cover`); the clip is a `video` with `controls`; each sound effect is an `audio` widget that is only
  heard (it has no size).
- `code/main.py` keeps a few numbers per player. `@ui.data('gallery')` hands them to the window. The sounds restart when
  their `restart` number changes (`bind: {"restart": "click_n"}`), and `muted`/`volume` follow the checkbox and the slider.
  Nothing in the code plays a sound or starts the clip.
- `on_end` of the video calls the action `clip_done`.

## Make your own files

Video needs Motion JPEG in an AVI, and its sound is a separate file:

```
ffmpeg -i in.mp4 -an -c:v mjpeg -q:v 5 -vf scale=480:-2,fps=15 clip.avi
ffmpeg -i in.mp4 -vn -ac 1 -ar 22050 clip.wav
```

Pictures: PNG, JPEG, BMP and GIF. Sound: OGG, WAV, MP3, FLAC, AIFF and AU. The guide lists the limits.
