# vØrk Music

A music player for [vØrk storage](https://github.com/vooteleaer/vork-storage): the songs in your libraries, wherever they came from (a computer's folder, Explorer, a mount), encrypted and synced like everything else, played straight from the network.

> A demo app of vØrk storage, the encrypted storage application of [vØrk](https://github.com/vooteleaer/vork). To try it, start with [getting started](https://github.com/vooteleaer/vork-storage/blob/main/docs/getting-started.md). It began as retifs Music, the same app for retifs, vØrk storage's predecessor; its history is kept here.

It reads a library the way the [Music Assistant provider](https://github.com/vooteleaer/vork/tree/main/clients/music-assistant) does, so both show one collection:

- **Songs** are records whose `mime` is `audio/…` (or whose path ends like an audio file, before the extractor has seen them). Their tags are the extractor's (`audio.title`, `audio.artist`, `audio.album`, `audio.albumartist`, `audio.track`, `audio.disc`, `audio.year`, `audio.genre`, `duration`); a missing one comes from the path, `Artist/Album (Year)/NN - Title.ext` (with `CD 2/` folders and `1-03` numbers).
- **Albums** are songs with one album name and album artist, in any of the libraries shown. Their **cover** is an image in the album's folder (`cover.*`, `folder.*`, `front.*`, …), or one that says `cover.album=<album key>`: the covers Music Assistant finds are saved that way.
- **Favourites** say `favorite = true`, a star in every vØrk storage app (and in Music Assistant).

`core/` talks to `vorkd` (vØrk storage's daemon) through libvork.

## Android

`android/` is the phone app (Kotlin, Compose, the core through JNI in `android/jni`, Media3 for playing):

- **Albums** as a grid of covers, **Artists**, **Songs** (shuffle all, starred only), search in each.
- An album: its cover, year, songs and length, Play and Shuffle, discs apart.
- **Playing**: a bar at the bottom while something plays; tap it for the whole player (the cover, where the song is, shuffle, previous, play/pause, next, repeat off/all/one, star, up next). Each song: play next, add to queue, go to album or artist.
- It plays on with the screen off, with the notification, lock screen, headset and Bluetooth buttons (a media session).
- Songs play in pieces as they're needed (256 KiB at a time): vorkd fetches what isn't on the phone from the library's other nodes, nearest first, so a song starts before it's all here and seeking works.
- The libraries are on the left (Announces above them, each a toggle; libraries without music aren't listed), and it reads again when a library changes, here or on another device.

On Android vorkd runs in the vØrk storage app (`org.vork.storage`), on the node of the vØrk app (`org.vork.net`); its sockets are out of this app's reach, so the app asks the storage app's access service (action `org.vork.storage.action.ACCESS`) for each connection: the user lets Music in once there (`org.vork.storage.action.LET_IN`), to the libraries chosen, and the storage app hands over connections made with its own app token for it (storage ADR-0004). Music asks to change them only to star songs.

Build: `powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 [-Abis arm64-v8a] [-Release]` (NDK 27, JDK 17, [vork-storage](https://github.com/vooteleaer/vork-storage) next to this repository: the core builds against its `crates/libvork`).

## License

[MIT](LICENSE)
