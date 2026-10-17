# Songs as text: "E4" is a note (name, optional #, octave), ":2" makes it last 2 beats (default 1), "-" is a rest.
# All of them are public domain melodies. parse() turns them into (note-block pitch, beats); songs are moved by whole
# octaves to fit the two octaves a note block can play (F#3 to F#5).
NAMES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
LOWEST = 54  # F#3, the lowest note block note (MIDI number)

SONGS = [
    ("Ode to Joy", 120, """
        E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 E4:1.5 D4:0.5 D4:2
        E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 D4:1.5 C4:0.5 C4:2"""),
    ("Twinkle Twinkle Little Star", 110, """
        C4 C4 G4 G4 A4 A4 G4:2 F4 F4 E4 E4 D4 D4 C4:2
        G4 G4 F4 F4 E4 E4 D4:2 G4 G4 F4 F4 E4 E4 D4:2
        C4 C4 G4 G4 A4 A4 G4:2 F4 F4 E4 E4 D4 D4 C4:2"""),
    ("Korobeiniki", 150, """
        E5 B4:0.5 C5:0.5 D5 C5:0.5 B4:0.5 A4 A4:0.5 C5:0.5 E5 D5:0.5 C5:0.5 B4:1.5 C5:0.5 D5 E5 C5 A4 A4:2
        -:0.5 D5 F5:0.5 A5 G5:0.5 F5:0.5 E5:1.5 C5:0.5 E5 D5:0.5 C5:0.5 B4 B4:0.5 C5:0.5 D5 E5 C5 A4 A4:2"""),
    ("Fur Elise (opening)", 130, """
        E5:0.5 D#5:0.5 E5:0.5 D#5:0.5 E5:0.5 B4:0.5 D5:0.5 C5:0.5 A4:1.5
        C4:0.5 E4:0.5 A4:0.5 B4:1.5 E4:0.5 G#4:0.5 B4:0.5 C5:1.5
        E4:0.5 E5:0.5 D#5:0.5 E5:0.5 D#5:0.5 E5:0.5 B4:0.5 D5:0.5 C5:0.5 A4:1.5
        C4:0.5 E4:0.5 A4:0.5 B4:1.5 E4:0.5 C5:0.5 B4:0.5 A4:2"""),
    ("Frere Jacques", 120, """
        C4 D4 E4 C4 C4 D4 E4 C4 E4 F4 G4:2 E4 F4 G4:2
        G4:0.5 A4:0.5 G4:0.5 F4:0.5 E4 C4 G4:0.5 A4:0.5 G4:0.5 F4:0.5 E4 C4
        C4 G3 C4:2 C4 G3 C4:2"""),
]

INSTRUMENTS = ["harp", "bell", "flute", "chime", "xylophone", "pling", "bit", "guitar", "banjo", "iron_xylophone"]


def midi(note):
    """'C#4' -> 61."""
    name, rest = note[0], note[1:]
    sharp = rest.startswith("#")
    octave = int(rest[1:] if sharp else rest)
    return 12 * (octave + 1) + NAMES[name] + (1 if sharp else 0)


def parse(text):
    """The song as [(pitch or None for a rest, beats)], moved by octaves into the note block range."""
    notes = []
    for token in text.split():
        name, _, length = token.partition(":")
        notes.append((None if name == "-" else midi(name), float(length or 1)))
    played = [n for n, _ in notes if n is not None]
    shift = 0
    while max(played) + shift > LOWEST + 24:
        shift -= 12
    while min(played) + shift < LOWEST:
        shift += 12
    return [(None if n is None else 2 ** ((n + shift - LOWEST - 12) / 12), beats) for n, beats in notes]


TRACKS = [(title, bpm, parse(text)) for title, bpm, text in SONGS]
