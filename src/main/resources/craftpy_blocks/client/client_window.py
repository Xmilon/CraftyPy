#: title: Window that runs on the player's computer (client)
#: category: Client side
#: doc: api.md#Windows
#: module: notepad
#: side: client
#: about: A window opened with Ctrl+N; its text is saved on the player's computer.
from craftpy import *


@window("{{name}}", title="Notepad", size=(300, 180))
class {{name}}_window(Window):
    def build(self, ctx):
        return VBox(TextArea(storage.get("{{name}}", ""), id="text"), Button("Save", on_click=self.save), spacing=6)

    def save(self, ctx):
        storage["{{name}}"] = ctx.values["text"]
        client.toast("Notepad", "Saved")


@keys.bind("Ctrl+N")
def open_{{name}}(ctx):
    client.open_window("{{name}}")
