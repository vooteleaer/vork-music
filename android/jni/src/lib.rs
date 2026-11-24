//! vØrk Music for Android: the music core to Kotlin through JNI
//! (`org.vork.music.Core`), answers as JSON (Kotlin parses them with
//! org.json).
//!
//! vorkd's socket is in the vØrk storage app's files, out of this app's reach:
//! every connection comes from the vØrk storage app's access service, asked
//! through `org.vork.music.Vork.connect()` (a descriptor, or -1).
#![cfg(unix)]

use std::cell::RefCell;
use std::collections::HashMap;
use std::os::fd::FromRawFd;
use std::sync::OnceLock;

use jni::objects::{GlobalRef, JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use vork_music_core::{Album, Library, Track};
use serde_json::{json, Value};

static VM: OnceLock<(JavaVM, GlobalRef)> = OnceLock::new();

thread_local! {
    /// A core per thread (each with its own small runtime).
    static LIB: RefCell<Option<Library>> = const { RefCell::new(None) };
}

fn connect() -> Result<std::os::unix::net::UnixStream, String> {
    let (vm, class) = VM.get().ok_or("not set up")?;
    let mut env = vm.attach_current_thread().map_err(|e| e.to_string())?;
    let class: &JClass = class.as_obj().into();
    let fd = env.call_static_method(class, "connect", "()I", &[]).and_then(|v| v.i()).map_err(|e| e.to_string())?;
    if fd < 0 {
        return Err("vØrk storage hasn't let this app in, or isn't running".into());
    }
    // SAFETY: the descriptor was detached from its ParcelFileDescriptor for us.
    Ok(unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) })
}

fn with<T>(f: impl FnOnce(&Library) -> T) -> T {
    LIB.with(|l| {
        let mut l = l.borrow_mut();
        f(l.get_or_insert_with(|| Library::with_connector(Box::new(connect))))
    })
}

fn arg(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn out(env: &mut JNIEnv, s: impl Into<String>) -> jstring {
    env.new_string(s.into()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn track_json(t: &Track) -> Value {
    json!({
        "ns": t.ns, "id": t.id, "title": t.title, "artist": t.artist, "album": t.album,
        "albumartist": t.albumartist, "track": t.track, "disc": t.disc, "year": t.year,
        "genre": t.genre, "duration": t.duration, "cap": t.cap, "mime": t.mime, "favorite": t.favorite,
    })
}

fn album_json(a: &Album) -> Value {
    json!({
        "key": a.key, "name": a.name, "artist": a.artist, "year": a.year,
        "cover": a.cover.as_ref().map(|c| json!({ "ns": c.ns, "thumb": c.thumb, "full": c.full })),
        "tracks": a.tracks.iter().map(track_json).collect::<Vec<_>>(),
    })
}

/// Called once: the class whose static `connect()` gives connections.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_init<'l>(env: JNIEnv<'l>, _: JClass<'l>, vork: JClass<'l>) {
    if VM.get().is_some() {
        return;
    }
    if let (Ok(vm), Ok(class)) = (env.get_java_vm(), env.new_global_ref(vork)) {
        let _ = VM.set((vm, class));
    }
}

/// The libraries this app was let in to: `[{name, writable}]`, or `{"error"}`.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_libraries<'l>(mut env: JNIEnv<'l>, _: JClass<'l>) -> jstring {
    let r = with(|l| match l.libraries() {
        Ok(names) => Value::Array(names.iter().map(|n| json!({ "name": n, "writable": l.writable(n) })).collect()),
        Err(e) => json!({ "error": e }),
    });
    out(&mut env, r.to_string())
}

/// The albums of library `ns` (each with its tracks and cover), or `{"error"}`.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_albums<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, ns: JString<'l>) -> jstring {
    let ns = arg(&mut env, &ns);
    let r = with(|l| match l.records(&ns) {
        Ok(recs) => Value::Array(vork_music_core::albums_of(&recs).iter().map(album_json).collect()),
        Err(e) => json!({ "error": e }),
    });
    out(&mut env, r.to_string())
}

/// Bytes `offset..offset + len` of `cap` in `ns` (fewer at its end, empty
/// past it), or null if they couldn't be had within `waitMs`.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_readRange<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, ns: JString<'l>, cap: JString<'l>, offset: jlong, len: jlong, wait_ms: jlong) -> jbyteArray {
    let (ns, cap) = (arg(&mut env, &ns), arg(&mut env, &cap));
    match with(|l| l.read_range(&ns, &cap, offset.max(0) as u64, len.max(0) as u64, wait_ms.max(0) as u64)) {
        Ok(data) => env.byte_array_from_slice(&data).map(JByteArray::into_raw).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Block `cap` of a record in `ns` if at most `max` bytes, else null.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_read<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, ns: JString<'l>, cap: JString<'l>, max: jlong) -> jbyteArray {
    let (ns, cap) = (arg(&mut env, &ns), arg(&mut env, &cap));
    match with(|l| l.read(&ns, &cap, max.max(0) as u64)) {
        Some(data) => env.byte_array_from_slice(&data).map(JByteArray::into_raw).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Stars a track, or not: "" when fine, else the error.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_setFavorite<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, ns: JString<'l>, id: JString<'l>, on: jboolean) -> jstring {
    let (ns, id) = (arg(&mut env, &ns), arg(&mut env, &id));
    let r = with(|l| l.set_favorite(&ns, &id, on == JNI_TRUE));
    out(&mut env, r.err().unwrap_or_default())
}

/// Each library's change count (`libraries` a JSON array): `{name: count}`.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_changeCounts<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, libraries: JString<'l>) -> jstring {
    let libraries: Vec<String> = serde_json::from_str(&arg(&mut env, &libraries)).unwrap_or_default();
    let r = with(|l| json!(l.change_counts(&libraries)));
    out(&mut env, r.to_string())
}

/// Where the core's own panics are written (ADR-0036): `dir`, each report
/// starting with `header`; the app sends them to vØrk storage at its next start.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_crashDir<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, dir: JString<'l>, header: JString<'l>) {
    let (dir, header) = (arg(&mut env, &dir), arg(&mut env, &header));
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        previous(info);
        let ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
        let thread = std::thread::current().name().unwrap_or("unnamed").to_string();
        let text = format!("{header}Panic in the music core, thread '{thread}': {info}\n\n--- backtrace ---\n{}", std::backtrace::Backtrace::force_capture());
        let part = format!("{dir}/.{ms}-app_panic.txt.part");
        if std::fs::write(&part, text).is_ok() {
            let _ = std::fs::rename(&part, format!("{dir}/{ms}-app_panic.txt"));
        }
    }));
}

/// A crash or trouble report to vØrk storage (storage ADR-0036): whether it took it.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_crashReport<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, kind: JString<'l>, source: JString<'l>, text: JString<'l>) -> jboolean {
    let (kind, source, text) = (arg(&mut env, &kind), arg(&mut env, &source), arg(&mut env, &text));
    with(|l| l.crash_report(&kind, &source, &text).is_ok()) as jboolean
}

/// Waits until a library in `known` (`{name: count}`) changes, for at most
/// `timeout_ms`: `{"changed", "known"}`, the counts brought up to date.
#[no_mangle]
pub extern "system" fn Java_org_vork_music_Core_waitChange<'l>(mut env: JNIEnv<'l>, _: JClass<'l>, known: JString<'l>, timeout_ms: jlong) -> jstring {
    let mut known: HashMap<String, u64> = serde_json::from_str(&arg(&mut env, &known)).unwrap_or_default();
    let changed = with(|l| l.wait_change(&mut known, std::time::Duration::from_millis(timeout_ms.max(0) as u64)));
    out(&mut env, json!({ "changed": changed, "known": known }).to_string())
}
