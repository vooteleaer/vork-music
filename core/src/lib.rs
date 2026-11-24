//! Music on vØrk storage: the tracks in the user's libraries, their albums and the
//! albums' covers, read the way the Music Assistant provider reads them, so
//! every player shows one collection:
//!
//! - A track is a record whose `mime` is `audio/…` (or, before the extractor
//!   has seen it, whose `path` ends like an audio file), with its content.
//! - Its tags are the extractor's (`audio.title`, `audio.artist`,
//!   `audio.album`, `audio.albumartist`, `audio.track`, `audio.disc`,
//!   `audio.year`, `audio.genre`, `duration`); each one missing comes from
//!   the path, `Artist/Album (Year)/NN - Title.ext`.
//! - An album's cover is an image record in its folder (`cover.*`,
//!   `folder.*`, `front.*`, …), or one that says `cover.album=<album key>`
//!   (the covers Music Assistant finds and saves).
//! - A favourite says `favorite = true` (a star in every vØrk storage app).

use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;

use libvork::{AttrValue, Client, RecordView, Request, Response};
use sha2::{Digest, Sha256};

pub const ME: &str = "me";

const AUDIO_EXT: &[&str] = &["mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "aac", "wav", "aif", "aiff", "alac", "wma", "ape", "wv", "mka", "dsf"];
const IMAGE_EXT: &[&str] = &["jpg", "jpeg", "png", "webp", "gif"];
/// Names a folder's cover goes by, the first preferred.
const COVER_NAMES: &[&str] = &["cover", "folder", "front", "album", "albumart"];

/// A record as read: where it lives, its attributes and blocks.
#[derive(Debug, Clone, PartialEq)]
pub struct Rec {
    pub ns: String,
    pub id: String,
    pub attrs: BTreeMap<String, AttrValue>,
    pub blocks: Vec<String>,
}

impl Rec {
    fn from_view(ns: &str, r: &RecordView) -> Rec {
        Rec { ns: ns.into(), id: r.id.clone(), attrs: r.attrs.iter().cloned().collect(), blocks: r.blocks.clone() }
    }

    pub fn text(&self, key: &str) -> Option<String> {
        match self.attrs.get(key)? {
            AttrValue::Str(s) if !s.trim().is_empty() => Some(s.trim().to_string()),
            AttrValue::Int(n) => Some(n.to_string()),
            AttrValue::Float(f) => Some(f.to_string()),
            _ => None,
        }
    }

    pub fn int(&self, key: &str) -> Option<i64> {
        match self.attrs.get(key)? {
            AttrValue::Int(n) if *n > 0 => Some(*n),
            AttrValue::Float(f) if *f > 0.0 => Some(f.round() as i64),
            // "3/12" (track of tracks) or "1999-05-01".
            AttrValue::Str(s) => s.trim().split(['/', '-']).next()?.trim().parse().ok().filter(|n| *n > 0),
            _ => None,
        }
    }

    fn path(&self) -> String {
        self.text("path").unwrap_or_default()
    }

    fn ext(&self) -> String {
        let path = self.path();
        let base = path.rsplit('/').next().unwrap_or("");
        match base.rsplit_once('.') {
            Some((_, e)) => e.to_lowercase(),
            None => String::new(),
        }
    }

    fn mime(&self) -> String {
        self.text("mime").unwrap_or_default().to_lowercase()
    }

    pub fn is_audio(&self) -> bool {
        let mime = self.mime();
        !self.blocks.is_empty() && (mime.starts_with("audio/") || (mime.is_empty() && AUDIO_EXT.contains(&self.ext().as_str())))
    }

    pub fn is_image(&self) -> bool {
        let mime = self.mime();
        !self.blocks.is_empty() && (mime.starts_with("image/") || (mime.is_empty() && IMAGE_EXT.contains(&self.ext().as_str())))
    }

    /// The block for `role` (`thumb`, `preview`; storage ADR-0026), else the content.
    pub fn block(&self, role: &str) -> Option<&String> {
        let i = match self.attrs.get(&format!("block:{role}")) {
            Some(AttrValue::Int(i)) if *i > 0 => *i as usize,
            _ => 0,
        };
        self.blocks.get(i).or_else(|| self.blocks.first())
    }

    pub fn favorite(&self) -> bool {
        matches!(self.attrs.get("favorite"), Some(AttrValue::Bool(true)))
    }
}

/// What a path says: `Artist/Album (Year)/[D-]NN - Title.ext`, with an
/// optional `CD 2` folder.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct PathTags {
    pub title: Option<String>,
    pub track: Option<i64>,
    pub disc: Option<i64>,
    pub album: Option<String>,
    pub albumartist: Option<String>,
    pub year: Option<i64>,
}

pub fn from_path(path: &str) -> PathTags {
    let parts: Vec<&str> = path.split('/').filter(|p| !p.is_empty()).collect();
    let mut out = PathTags::default();
    let Some((file, mut folders)) = parts.split_last() else { return out };
    let mut stem = match file.rsplit_once('.') {
        Some((s, _)) if !s.is_empty() => s,
        _ => file,
    };
    // `NN - Title`, `NN. Title`, `D-NN Title`, `D.NN_Title`.
    if let Some((disc, track, rest)) = track_prefix(stem) {
        out.disc = disc;
        out.track = Some(track);
        stem = rest;
    }
    out.title = Some(stem.trim().to_string()).filter(|s| !s.is_empty());
    if let Some((last, rest)) = folders.split_last() {
        let l = last.to_lowercase();
        let word = ["cd", "disc", "disk"].iter().find(|w| l.starts_with(**w));
        if let Some(w) = word {
            let n = l[w.len()..].trim();
            if !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()) {
                out.disc = out.disc.or(n.parse().ok());
                folders = rest;
            }
        }
    }
    if let Some((album, rest)) = folders.split_last() {
        let mut album = album.trim();
        // `Album (1975)` or `Album [1975]`.
        let b = album.as_bytes();
        if b.len() >= 6 && matches!(b[b.len() - 1], b')' | b']') && matches!(b[b.len() - 6], b'(' | b'[') && album[b.len() - 5..b.len() - 1].chars().all(|c| c.is_ascii_digit()) {
            out.year = album[b.len() - 5..b.len() - 1].parse().ok();
            album = album[..b.len() - 6].trim_end();
        }
        out.album = Some(album.to_string()).filter(|s| !s.is_empty());
        if let Some(artist) = rest.last() {
            out.albumartist = Some(artist.trim().to_string()).filter(|s| !s.is_empty());
        }
    }
    out
}

/// `(disc, track, rest)` from the start of a file name.
fn track_prefix(stem: &str) -> Option<(Option<i64>, i64, &str)> {
    let s = stem.trim_start();
    let digits = |s: &str| s.chars().take_while(|c| c.is_ascii_digit()).count();
    let n = digits(s);
    if n == 0 || n > 3 {
        return None;
    }
    let (mut disc, mut track, mut rest) = (None, s[..n].parse().ok()?, &s[n..]);
    // `1-03` / `1.03`: a disc, then the track.
    if n <= 2 && (rest.starts_with('-') || rest.starts_with('.')) {
        let m = digits(&rest[1..]);
        if (1..=3).contains(&m) {
            disc = Some(track);
            track = rest[1..1 + m].parse().ok()?;
            rest = &rest[1 + m..];
        }
    }
    // Then a separator, and something after it.
    let after = rest.trim_start_matches(|c: char| c == '-' || c == '.' || c == '_' || c == ' ');
    if after.len() == rest.len() || after.trim().is_empty() {
        return None;
    }
    Some((disc, track, after))
}

/// A track: a record and its tags.
#[derive(Debug, Clone, PartialEq)]
pub struct Track {
    pub ns: String,
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: Option<String>,
    pub albumartist: Option<String>,
    pub track: Option<i64>,
    pub disc: Option<i64>,
    pub year: Option<i64>,
    pub genre: Option<String>,
    /// Seconds.
    pub duration: Option<i64>,
    /// Its content: a read-cap (the size is its last field).
    pub cap: String,
    pub mime: String,
    pub path: String,
    pub favorite: bool,
}

impl Track {
    pub fn of(r: &Rec) -> Option<Track> {
        if !r.is_audio() {
            return None;
        }
        let path = r.path();
        let p = from_path(&path);
        let albumartist = r.text("audio.albumartist").or(p.albumartist);
        let title = r.text("audio.title").or(p.title).or_else(|| r.text("name")).unwrap_or_else(|| r.id.chars().take(8).collect());
        let artist = r.text("audio.artist").or_else(|| albumartist.clone()).unwrap_or_default();
        Some(Track {
            ns: r.ns.clone(),
            id: r.id.clone(),
            title,
            artist,
            album: r.text("audio.album").or(p.album),
            albumartist,
            track: r.int("audio.track").or(p.track),
            disc: r.int("audio.disc").or(p.disc),
            year: r.int("audio.year").or(p.year),
            genre: r.text("audio.genre"),
            duration: r.int("duration"),
            cap: r.blocks[0].clone(),
            mime: r.mime(),
            path,
            favorite: r.favorite(),
        })
    }

    fn folder(&self) -> &str {
        self.path.rsplit_once('/').map(|(f, _)| f).unwrap_or("")
    }

    /// The artist its album goes under: the album artist, else its first artist.
    pub fn album_artist(&self) -> String {
        self.albumartist.clone().unwrap_or_else(|| first_artist(&self.artist).unwrap_or_else(|| "Various Artists".into()))
    }
}

/// The first of the artists a tag names (`A; B`, `A / B`).
fn first_artist(s: &str) -> Option<String> {
    s.replace(" / ", ";").split(';').map(str::trim).find(|a| !a.is_empty()).map(String::from)
}

/// An album key as the Music Assistant provider makes it: sha256 of the
/// parts (lower case, trimmed, NUL between), its first 20 hex digits.
pub fn album_key(artist: &str, name: &str) -> String {
    let joined = [artist, name].iter().map(|p| p.trim().to_lowercase()).collect::<Vec<_>>().join("\0");
    Sha256::digest(joined.as_bytes()).iter().take(10).map(|b| format!("{b:02x}")).collect()
}

/// An album cover: an image record, its small block and its whole content.
#[derive(Debug, Clone, PartialEq)]
pub struct Cover {
    pub ns: String,
    pub thumb: String,
    pub full: String,
}

impl Cover {
    fn of(r: &Rec) -> Option<Cover> {
        Some(Cover { ns: r.ns.clone(), thumb: r.block("thumb")?.clone(), full: r.blocks.first()?.clone() })
    }
}

/// An album: tracks with one album name and album artist (in any library).
#[derive(Debug, Clone, PartialEq)]
pub struct Album {
    /// [`album_key`]; "" for the tracks without an album.
    pub key: String,
    pub name: String,
    pub artist: String,
    pub year: Option<i64>,
    pub cover: Option<Cover>,
    /// In play order: disc, track, title.
    pub tracks: Vec<Track>,
}

impl Album {
    pub fn duration(&self) -> i64 {
        self.tracks.iter().filter_map(|t| t.duration).sum()
    }
}

/// The tracks in `recs` as albums (the cover of each found among them),
/// sorted by artist, year and name; the tracks without an album last.
pub fn albums_of(recs: &[Rec]) -> Vec<Album> {
    // Covers by folder (the best name wins) and by the key they say they're for.
    let mut by_folder: HashMap<(String, String), (usize, &Rec)> = HashMap::new();
    let mut by_key: HashMap<String, &Rec> = HashMap::new();
    for r in recs.iter().filter(|r| r.is_image()) {
        if let Some(k) = r.text("cover.album") {
            by_key.insert(k, r);
        }
        let path = r.path();
        let (folder, file) = path.rsplit_once('/').unwrap_or(("", &path));
        let stem = file.rsplit_once('.').map(|(s, _)| s).unwrap_or(file).to_lowercase();
        if let Some(rank) = COVER_NAMES.iter().position(|n| *n == stem) {
            let slot = by_folder.entry((r.ns.clone(), folder.to_string())).or_insert((rank, r));
            if rank < slot.0 {
                *slot = (rank, r);
            }
        }
    }
    let mut albums: BTreeMap<String, Album> = BTreeMap::new();
    let mut seen = std::collections::HashSet::new();
    for t in recs.iter().filter_map(Track::of) {
        if !seen.insert((t.ns.clone(), t.id.clone())) {
            continue;
        }
        let (key, name, artist) = match &t.album {
            Some(name) => {
                let artist = t.album_artist();
                (album_key(&artist, name), name.clone(), artist)
            }
            None => (String::new(), "Other tracks".to_string(), String::new()),
        };
        let a = albums.entry(key.clone()).or_insert_with(|| Album { key: key.clone(), name, artist, year: None, cover: None, tracks: Vec::new() });
        a.year = a.year.or(t.year);
        if a.cover.is_none() && !key.is_empty() {
            let found = by_key.get(&key).copied().or_else(|| by_folder.get(&(t.ns.clone(), t.folder().to_string())).map(|(_, r)| *r));
            a.cover = found.and_then(Cover::of);
        }
        a.tracks.push(t);
    }
    let mut out: Vec<Album> = albums.into_values().collect();
    for a in &mut out {
        a.tracks.sort_by(|x, y| {
            (x.disc.unwrap_or(1), x.track.unwrap_or(i64::MAX), x.title.to_lowercase()).cmp(&(y.disc.unwrap_or(1), y.track.unwrap_or(i64::MAX), y.title.to_lowercase()))
        });
    }
    out.sort_by(|x, y| {
        (x.key.is_empty(), sort_name(&x.artist), x.year.unwrap_or(0), x.name.to_lowercase()).cmp(&(y.key.is_empty(), sort_name(&y.artist), y.year.unwrap_or(0), y.name.to_lowercase()))
    });
    out
}

/// An artist as sorted: without a leading "The ".
fn sort_name(s: &str) -> String {
    let l = s.trim().to_lowercase();
    l.strip_prefix("the ").map(String::from).unwrap_or(l)
}

/// Opens a connection to `vorkd` some other way than its socket (on
/// Android: from the vØrk storage app's access service).
#[cfg(unix)]
pub type Connector = Box<dyn Fn() -> Result<std::os::unix::net::UnixStream, String> + Send + Sync>;

/// The connection to `vorkd`.
pub struct Library {
    rt: tokio::runtime::Runtime,
    store: PathBuf,
    /// The connection kept between calls (a track plays as many reads).
    kept: std::cell::RefCell<Option<Client>>,
    #[cfg(unix)]
    connector: Option<Connector>,
}

impl Default for Library {
    fn default() -> Self {
        Self::new()
    }
}

impl Library {
    pub fn new() -> Self {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("a tokio runtime");
        Library {
            rt,
            store: libvork::default_store_dir(),
            kept: std::cell::RefCell::new(None),
            #[cfg(unix)]
            connector: None,
        }
    }

    /// Connections from `connector` instead of the daemon's socket.
    #[cfg(unix)]
    pub fn with_connector(connector: Connector) -> Self {
        Library { connector: Some(connector), ..Self::new() }
    }

    fn client(&self) -> Result<Client, String> {
        #[cfg(unix)]
        if let Some(connect) = &self.connector {
            let stream = connect()?;
            return self.rt.block_on(async { Client::from_std(stream) }).map_err(|e| e.to_string());
        }
        self.rt.block_on(Client::connect(&self.store)).map_err(|e| e.to_string())
    }

    /// `req` on the kept connection; once more on a new one if that one broke
    /// (vorkd restarted, the access service handed over a new one).
    fn request(&self, req: Request) -> Result<Response, String> {
        for attempt in 0..2 {
            let kept = self.kept.borrow_mut().take();
            let mut c = match kept {
                Some(c) => c,
                None => self.client()?,
            };
            match self.rt.block_on(c.call(req.clone())) {
                Ok(Response::Error { message }) => {
                    *self.kept.borrow_mut() = Some(c);
                    return Err(message);
                }
                Ok(r) => {
                    *self.kept.borrow_mut() = Some(c);
                    return Ok(r);
                }
                Err(e) if attempt == 1 => return Err(e.to_string()),
                Err(_) => continue,
            }
        }
        unreachable!()
    }

    /// Every library this app may use, by name (not `me` or channels).
    pub fn libraries(&self) -> Result<Vec<String>, String> {
        let mut names = match self.request(Request::Namespaces)? {
            Response::Names { names } => names,
            _ => Vec::new(),
        };
        names.retain(|n| n != ME && !n.starts_with("ch-"));
        names.sort_by_key(|n| n.to_lowercase());
        Ok(names)
    }

    /// Whether this device may change library `ns`.
    pub fn writable(&self, ns: &str) -> bool {
        match self.request(Request::NsInfo { ns: ns.into() }) {
            Ok(Response::Namespace(v)) => v.writers.iter().any(|(w, r)| *w == v.own_writer && r.iter().any(|x| x == "write")),
            _ => false,
        }
    }

    /// The files in library `ns` (records with a path: tracks and covers).
    pub fn records(&self, ns: &str) -> Result<Vec<Rec>, String> {
        match self.request(Request::Query { ns: ns.into(), terms: vec!["has:path".into()], at: None })? {
            Response::Records { records } => Ok(records.iter().map(|r| Rec::from_view(ns, r)).collect()),
            other => Err(format!("unexpected answer: {other:?}")),
        }
    }

    /// Bytes `offset..offset + len` of block `cap` of a record in `ns`
    /// (fewer at its end); what isn't here yet is fetched, for at most `wait_ms`.
    pub fn read_range(&self, ns: &str, cap: &str, offset: u64, len: u64, wait_ms: u64) -> Result<Vec<u8>, String> {
        match self.request(Request::ReadRange { cap: cap.into(), ns: Some(ns.into()), offset, len, wait_ms })? {
            Response::Range { data } => Ok(data),
            other => Err(format!("unexpected answer: {other:?}")),
        }
    }

    /// The whole of block `cap` if at most `max` bytes.
    pub fn read(&self, ns: &str, cap: &str, max: u64) -> Option<Vec<u8>> {
        let size: u64 = cap.rsplit(':').next()?.parse().ok()?;
        if size > max {
            return None;
        }
        self.read_range(ns, cap, 0, size, 30_000).ok()
    }

    /// A crash or trouble report to vØrk storage (storage ADR-0036), which keeps it for the
    /// user's crash library.
    pub fn crash_report(&self, kind: &str, source: &str, text: &str) -> Result<(), String> {
        self.request(Request::CrashReport { kind: kind.into(), source: source.into(), text: text.into() }).map(|_| ())
    }

    pub fn set_favorite(&self, ns: &str, id: &str, on: bool) -> Result<(), String> {
        let (attrs, unset) = if on { (vec![("favorite".to_string(), AttrValue::Bool(true))], vec![]) } else { (vec![], vec!["favorite".to_string()]) };
        self.request(Request::RecordSet { ns: ns.into(), id: id.into(), attrs, unset, data: None }).map(|_| ())
    }

    /// Each library's change count: a front end reads again when these differ.
    pub fn change_counts(&self, libraries: &[String]) -> HashMap<String, u64> {
        let Ok(mut c) = self.client() else { return HashMap::new() };
        libraries
            .iter()
            .filter_map(|ns| match self.rt.block_on(c.call(Request::Watch { ns: ns.clone(), since: 0, timeout_ms: 0 })) {
                Ok(Response::Changed { count }) => Some((ns.clone(), count)),
                _ => None,
            })
            .collect()
    }

    /// Waits until one of the libraries in `known` (with their change counts)
    /// changes, for at most `timeout`: whether one did, the counts in `known`
    /// brought up to date.
    pub fn wait_change(&self, known: &mut HashMap<String, u64>, timeout: std::time::Duration) -> bool {
        let watches: Vec<_> = known.iter().filter_map(|(ns, n)| self.client().ok().map(|c| (c, ns.clone(), *n))).collect();
        if watches.is_empty() {
            std::thread::sleep(timeout.min(std::time::Duration::from_secs(5)));
            return false;
        }
        match self.rt.block_on(libvork::watch_any(watches, timeout)) {
            Ok(Some((ns, count))) => {
                known.insert(ns, count);
                // Changes come in bursts (a sync merging many): one read for them all.
                std::thread::sleep(std::time::Duration::from_secs(2));
                let libraries: Vec<String> = known.keys().cloned().collect();
                known.extend(self.change_counts(&libraries));
                true
            }
            Ok(None) => false,
            Err(_) => {
                std::thread::sleep(std::time::Duration::from_secs(5));
                false
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(ns: &str, id: &str, attrs: &[(&str, AttrValue)], blocks: &[&str]) -> Rec {
        Rec { ns: ns.into(), id: id.into(), attrs: attrs.iter().map(|(k, v)| (k.to_string(), v.clone())).collect(), blocks: blocks.iter().map(|s| s.to_string()).collect() }
    }

    fn s(v: &str) -> AttrValue {
        AttrValue::Str(v.into())
    }

    #[test]
    fn paths_say_artist_album_year_and_track() {
        let p = from_path("Bob Dylan/Blood on the Tracks (1975)/03 - You're a Big Girl Now.flac");
        assert_eq!(p.albumartist.as_deref(), Some("Bob Dylan"));
        assert_eq!(p.album.as_deref(), Some("Blood on the Tracks"));
        assert_eq!(p.year, Some(1975));
        assert_eq!(p.track, Some(3));
        assert_eq!(p.title.as_deref(), Some("You're a Big Girl Now"));
        let p = from_path("/music/Pink Floyd/The Wall/CD 2/1-04 Hey You.mp3");
        assert_eq!((p.album.as_deref(), p.albumartist.as_deref(), p.disc, p.track), (Some("The Wall"), Some("Pink Floyd"), Some(1), Some(4)));
        let p = from_path("Artist/Album/CD2/05. Song.mp3");
        assert_eq!((p.disc, p.track, p.title.as_deref()), (Some(2), Some(5), Some("Song")));
        // A number that is the title.
        assert_eq!(from_path("1999.mp3").title.as_deref(), Some("1999"));
        assert_eq!(from_path("Prince/1999 [1982]/01 1999.mp3").title.as_deref(), Some("1999"));
    }

    #[test]
    fn albums_take_their_cover_from_the_folder_or_a_saved_one() {
        let recs = vec![
            rec("music", "t2", &[("path", s("Dylan/Blood (1975)/02 - Two.flac")), ("mime", s("audio/flac"))], &["c2"]),
            rec("music", "t1", &[("path", s("Dylan/Blood (1975)/01 - One.flac")), ("audio.title", s("Tangled")), ("duration", AttrValue::Int(300))], &["c1"]),
            rec("music", "i1", &[("path", s("Dylan/Blood (1975)/folder.jpg")), ("mime", s("image/jpeg")), ("block:thumb", AttrValue::Int(1))], &["full", "thumb"]),
            rec("music", "i2", &[("path", s("Dylan/Blood (1975)/cover.jpg")), ("mime", s("image/jpeg"))], &["cover"]),
            rec("music", "t3", &[("path", s("X/Y/01 - Z.mp3")), ("mime", s("audio/mpeg"))], &["c3"]),
            rec("music", "i3", &[("path", s("Covers/y.jpg")), ("mime", s("image/jpeg")), ("cover.album", s(&album_key("X", "Y")))], &["ycover"]),
            rec("music", "t4", &[("path", s("loose.mp3"))], &["c4"]),
            rec("music", "n", &[("path", s("notes.txt"))], &["c5"]),
        ];
        let albums = albums_of(&recs);
        let names: Vec<_> = albums.iter().map(|a| (a.artist.as_str(), a.name.as_str())).collect();
        assert_eq!(names, vec![("Dylan", "Blood"), ("X", "Y"), ("", "Other tracks")]);
        let blood = &albums[0];
        assert_eq!(blood.year, Some(1975));
        assert_eq!(blood.tracks.iter().map(|t| t.title.as_str()).collect::<Vec<_>>(), vec!["Tangled", "Two"]);
        assert_eq!(blood.cover.as_ref().map(|c| c.thumb.as_str()), Some("cover"));
        assert_eq!(blood.duration(), 300);
        assert_eq!(albums[1].cover.as_ref().map(|c| c.full.as_str()), Some("ycover"));
        assert!(albums[2].cover.is_none());
    }

    #[test]
    fn album_keys_match_the_music_assistant_provider() {
        // Python: hashlib.sha256("bob dylan\0blood on the tracks".encode()).hexdigest()[:20]
        let want: String = Sha256::digest(b"bob dylan\0blood on the tracks").iter().take(10).map(|b| format!("{b:02x}")).collect();
        assert_eq!(album_key(" Bob Dylan", "Blood on the Tracks "), want);
        assert_eq!(want.len(), 20);
        // From Python, as the provider computes it.
        assert_eq!(album_key("X", "Y"), "ce3890a816f5237a17aa");
    }

    #[test]
    fn tags_win_over_the_path() {
        let r = rec(
            "m",
            "t",
            &[("path", s("A/B/01 - C.mp3")), ("mime", s("audio/mpeg")), ("audio.artist", s("D; E")), ("audio.album", s("F")), ("audio.track", s("7/12"))],
            &["cap"],
        );
        let t = Track::of(&r).unwrap();
        assert_eq!((t.artist.as_str(), t.album.as_deref(), t.track, t.album_artist()), ("D; E", Some("F"), Some(7), "A".to_string()));
        let r = rec("m", "t", &[("mime", s("audio/mpeg")), ("audio.artist", s("D / E"))], &["cap"]);
        assert_eq!(Track::of(&r).unwrap().album_artist(), "D");
    }
}
