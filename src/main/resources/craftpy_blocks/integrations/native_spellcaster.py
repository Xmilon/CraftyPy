#: title: Native Iron's spellcaster variant
#: category: Iron's Spellbooks
#: doc: api.md#NativeMob
#: module: native_spellcaster
#: about: Retains the original necromancer entity type, spells, AI and renderer.
#: side: server
#: keywords: 
#: mods: irons_spellbooks
from craftpy import *

{{name}} = NativeMob('{{name}}', base='irons_spellbooks:necromancer', state={'variant':'apprentice'})
