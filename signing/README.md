# Dev signing key

`dev.jks` signs dev builds from the Release workflow when the repository has no real signing key
in its secrets. It is **not secret** (password `nitrate-dev`, alias `nitrate-dev`), like Android's
debug key: its only job is to stay the same between builds so each one installs over the last.

Builds signed with it use their own application IDs (`dev.nitrate.app.dev`, `dev.nitrate.tv.dev`),
so they can never replace an app signed with the real release key. Only install them from this
repository's Releases page.
