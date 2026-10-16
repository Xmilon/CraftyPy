#: title: Save data in a file
#: category: Web
#: doc: api.md#Files
#: module: notes
#: about: /{{id}}:{{name}} <text> appends a note to a file in the project's data folder. Declare "files" first.
# project.json: "permissions": {"files": true}
from craftpy import *


@command("{{name}}")
def {{name}}(ctx, text: str):
    """/{{id}}:{{name}} <text> - save a note."""
    notes = files.read_json("notes.json", default=[])
    notes.append({"by": ctx.player.name, "text": text})
    files.write_json("notes.json", notes)
    ctx.player.say("Saved note", len(notes))
