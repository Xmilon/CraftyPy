# Third-party software in CraftPy

CraftPy itself is licensed under the GNU General Public License v3.0 (see `LICENSE`). The jars also carry the
following software, each under its own license.

| Software | Where | License |
|---|---|---|
| CPython 3.13 (python-build-standalone builds) | `craftpy_python/` in the jars with Python | Python Software Foundation License Version 2 — https://docs.python.org/3/license.html |
| JLayer 1.0.1 (MP3 decoder) | `META-INF/jarjar/jlayer-1.0.1.jar` | GNU Lesser General Public License v2.1 — https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |
| jFLAC 1.5.2 (FLAC decoder) | `META-INF/jarjar/jflac-codec-1.5.2.jar` | Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0 |

JLayer and jFLAC are shipped unmodified as separate jars inside the mod jar; you may replace them with other builds of
the same libraries. The python-build-standalone archives also contain the licenses of the libraries CPython is built
with (OpenSSL, SQLite, zlib, libffi, ...) in their own folders.

Optional mods CraftPy works with (Modern UI, GeckoLib, JEI, Curios and others) are not included; they keep their own
licenses. The NeoForge MDK template files are under the MIT license in `TEMPLATE_LICENSE.txt`.
