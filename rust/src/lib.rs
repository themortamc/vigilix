//! Vigilix core: criptografía y hashing para la app Android (vía JNI).
//!
//! Formato del texto cifrado (versión 1):
//!     "vgx1:" + hex( salt[16] || nonce[12] || ciphertext || tag[16] )
//!
//! - Clave   = Argon2id(contraseña maestra, salt aleatorio POR MENSAJE)
//! - Cifrado = ChaCha20-Poly1305, con "vigilix/v1" como dato autenticado (AAD)
//!
//! Bóveda de contraseñas (formato 1):
//!     "vgv1:" + hex( salt[16] || nonce[12] || ciphertext || tag[16] )
//!
//! - La clave derivada (Argon2id) vive SOLO en esta capa, en una sesión que se
//!   borra al bloquear. Kotlin nunca ve la clave salvo que pida exportarla para
//!   envolverla con la huella (Android Keystore).
//! - AAD distinto ("vigilix/vault/v1") para que un texto cifrado suelto no se
//!   pueda hacer pasar por una bóveda.
//!
//! Base de firmas: archivo binario con SHA-256 de 32 bytes, ordenados, sin
//! duplicados. La búsqueda es binaria directamente sobre el archivo.
//!
//! Reglas de esta capa:
//! - Nunca se devuelve texto de error como si fuera un resultado: ante cualquier
//!   fallo las funciones JNI devuelven `null`.
//! - Ningún panic cruza la frontera JNI (se atrapan con `catch_unwind`).
//! - Claves, contraseñas y textos en claro se borran de la memoria al soltarlos.

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use rand::{rngs::OsRng, Rng, RngCore};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use zeroize::Zeroizing;

const PREFIX: &str = "vgx1:";
const AAD: &[u8] = b"vigilix/v1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const KEY_LEN: usize = 32;
const TAG_LEN: usize = 16;

// Argon2id: 64 MiB de memoria, 3 pasadas, 1 hilo.
const ARGON_M_KIB: u32 = 64 * 1024;
const ARGON_T: u32 = 3;
const ARGON_P: u32 = 1;

const MIN_PASSWORD_LEN: usize = 8;
const MAX_PASSWORD_LEN: usize = 128;
const CHARSET: &[u8] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?";

const NULL_STR: jstring = std::ptr::null_mut();

// ---------------------------------------------------------------------------
// Lógica pura (sin JNI): fácil de probar
// ---------------------------------------------------------------------------

fn derive_key(password: &[u8], salt: &[u8]) -> Option<Zeroizing<[u8; KEY_LEN]>> {
    let params = Params::new(ARGON_M_KIB, ARGON_T, ARGON_P, Some(KEY_LEN)).ok()?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = Zeroizing::new([0u8; KEY_LEN]);
    argon2.hash_password_into(password, salt, &mut key[..]).ok()?;
    Some(key)
}

pub fn encrypt_text(password: &str, plaintext: &str) -> Option<String> {
    if password.is_empty() {
        return None;
    }
    let mut salt = [0u8; SALT_LEN];
    let mut nonce_bytes = [0u8; NONCE_LEN];
    OsRng.try_fill_bytes(&mut salt).ok()?;
    OsRng.try_fill_bytes(&mut nonce_bytes).ok()?;

    let key = derive_key(password.as_bytes(), &salt)?;
    let cipher = ChaCha20Poly1305::new_from_slice(&key[..]).ok()?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce_bytes),
            Payload {
                msg: plaintext.as_bytes(),
                aad: AAD,
            },
        )
        .ok()?;

    let mut blob = Vec::with_capacity(SALT_LEN + NONCE_LEN + ciphertext.len());
    blob.extend_from_slice(&salt);
    blob.extend_from_slice(&nonce_bytes);
    blob.extend_from_slice(&ciphertext);
    Some(format!("{}{}", PREFIX, hex::encode(blob)))
}

pub fn decrypt_text(password: &str, payload: &str) -> Option<String> {
    let hex_part = payload.trim().strip_prefix(PREFIX)?;
    let blob = hex::decode(hex_part).ok()?;
    if blob.len() < SALT_LEN + NONCE_LEN + TAG_LEN {
        return None;
    }
    let (salt, rest) = blob.split_at(SALT_LEN);
    let (nonce_bytes, ciphertext) = rest.split_at(NONCE_LEN);

    let key = derive_key(password.as_bytes(), salt)?;
    let cipher = ChaCha20Poly1305::new_from_slice(&key[..]).ok()?;
    let plain = Zeroizing::new(
        cipher
            .decrypt(
                Nonce::from_slice(nonce_bytes),
                Payload {
                    msg: ciphertext,
                    aad: AAD,
                },
            )
            .ok()?,
    );
    // UTF-8 estricto: si no es texto válido, falla en vez de alterar datos.
    String::from_utf8(plain.to_vec()).ok()
}

fn has_all_classes(bytes: &[u8]) -> bool {
    bytes.iter().any(|c| c.is_ascii_uppercase())
        && bytes.iter().any(|c| c.is_ascii_lowercase())
        && bytes.iter().any(|c| c.is_ascii_digit())
        && bytes.iter().any(|c| !c.is_ascii_alphanumeric())
}

/// Contraseña aleatoria con entropía del sistema operativo. Garantiza al menos
/// una mayúscula, una minúscula, un número y un símbolo (muchos sitios lo exigen).
pub fn generate_password(length: usize) -> Option<Zeroizing<String>> {
    let len = length.clamp(MIN_PASSWORD_LEN, MAX_PASSWORD_LEN);
    for _ in 0..1000 {
        let mut candidate = Zeroizing::new(String::with_capacity(len));
        for _ in 0..len {
            let idx = OsRng.gen_range(0..CHARSET.len());
            candidate.push(CHARSET[idx] as char);
        }
        if has_all_classes(candidate.as_bytes()) {
            return Some(candidate);
        }
    }
    None
}

pub fn sha256_of_file(mut file: File) -> Option<String> {
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buffer[..n]),
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return None,
        }
    }
    Some(hex::encode(hasher.finalize()))
}

// ---------------------------------------------------------------------------
// Bóveda: sesión y formato
// ---------------------------------------------------------------------------

const VAULT_PREFIX: &str = "vgv1:";
const VAULT_AAD: &[u8] = b"vigilix/vault/v1";

struct Session {
    key: Zeroizing<[u8; KEY_LEN]>,
    salt: [u8; SALT_LEN],
}

static SESSION: Mutex<Option<Session>> = Mutex::new(None);

fn session() -> MutexGuard<'static, Option<Session>> {
    // Si otro hilo entró en pánico con el candado tomado, se sigue usando el valor.
    SESSION.lock().unwrap_or_else(|e| e.into_inner())
}

fn seal(key: &[u8; KEY_LEN], salt: &[u8; SALT_LEN], plaintext: &[u8]) -> Option<String> {
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.try_fill_bytes(&mut nonce).ok()?;
    let cipher = ChaCha20Poly1305::new_from_slice(key).ok()?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: VAULT_AAD,
            },
        )
        .ok()?;
    let mut blob = Vec::with_capacity(SALT_LEN + NONCE_LEN + ciphertext.len());
    blob.extend_from_slice(salt);
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(&ciphertext);
    Some(format!("{}{}", VAULT_PREFIX, hex::encode(blob)))
}

struct Parsed {
    salt: [u8; SALT_LEN],
    nonce: [u8; NONCE_LEN],
    ciphertext: Vec<u8>,
}

fn parse_vault(blob: &str) -> Option<Parsed> {
    let hex_part = blob.trim().strip_prefix(VAULT_PREFIX)?;
    let raw = hex::decode(hex_part).ok()?;
    if raw.len() < SALT_LEN + NONCE_LEN + TAG_LEN {
        return None;
    }
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    salt.copy_from_slice(&raw[..SALT_LEN]);
    nonce.copy_from_slice(&raw[SALT_LEN..SALT_LEN + NONCE_LEN]);
    Some(Parsed {
        salt,
        nonce,
        ciphertext: raw[SALT_LEN + NONCE_LEN..].to_vec(),
    })
}

fn unseal(key: &[u8; KEY_LEN], parsed: &Parsed) -> Option<Zeroizing<Vec<u8>>> {
    let cipher = ChaCha20Poly1305::new_from_slice(key).ok()?;
    let plain = cipher
        .decrypt(
            Nonce::from_slice(&parsed.nonce),
            Payload {
                msg: &parsed.ciphertext,
                aad: VAULT_AAD,
            },
        )
        .ok()?;
    Some(Zeroizing::new(plain))
}

/// Crea (o re-cifra con otra clave maestra) una bóveda. Deja la sesión abierta.
pub fn vault_create(password: &str, plaintext: &[u8]) -> Option<String> {
    if password.is_empty() {
        return None;
    }
    let mut salt = [0u8; SALT_LEN];
    OsRng.try_fill_bytes(&mut salt).ok()?;
    let key = derive_key(password.as_bytes(), &salt)?;
    let blob = seal(&key, &salt, plaintext)?;
    *session() = Some(Session { key, salt });
    Some(blob)
}

/// Abre una bóveda con la clave maestra. Si sale bien, deja la sesión abierta.
pub fn vault_open(password: &str, blob: &str) -> Option<Zeroizing<Vec<u8>>> {
    let parsed = parse_vault(blob)?;
    let key = derive_key(password.as_bytes(), &parsed.salt)?;
    let plain = unseal(&key, &parsed)?;
    *session() = Some(Session {
        key,
        salt: parsed.salt,
    });
    Some(plain)
}

/// Descifra una bóveda externa (por ejemplo, una copia importada) SIN tocar la sesión
/// actual: la clave de la bóveda abierta no cambia.
pub fn vault_peek(password: &str, blob: &str) -> Option<Zeroizing<Vec<u8>>> {
    let parsed = parse_vault(blob)?;
    let key = derive_key(password.as_bytes(), &parsed.salt)?;
    unseal(&key, &parsed)
}

/// Abre la bóveda con la clave derivada (la que se guardó protegida por la huella).
pub fn vault_open_with_key(key_bytes: &[u8], blob: &str) -> Option<Zeroizing<Vec<u8>>> {
    if key_bytes.len() != KEY_LEN {
        return None;
    }
    let parsed = parse_vault(blob)?;
    let mut key = Zeroizing::new([0u8; KEY_LEN]);
    key.copy_from_slice(key_bytes);
    let plain = unseal(&key, &parsed)?;
    *session() = Some(Session {
        key,
        salt: parsed.salt,
    });
    Some(plain)
}

/// Cifra el contenido actual con la clave de la sesión (nuevo nonce, mismo salt).
pub fn vault_save(plaintext: &[u8]) -> Option<String> {
    let guard = session();
    let s = guard.as_ref()?;
    seal(&s.key, &s.salt, plaintext)
}

pub fn vault_lock() {
    *session() = None;
}

pub fn vault_is_unlocked() -> bool {
    session().is_some()
}

pub fn vault_export_key() -> Option<Zeroizing<Vec<u8>>> {
    let guard = session();
    let s = guard.as_ref()?;
    Some(Zeroizing::new(s.key.to_vec()))
}

// ---------------------------------------------------------------------------
// Base de firmas (hashes de malware conocido)
// ---------------------------------------------------------------------------

const HASH_LEN: usize = 32;

pub const DB_OK_IO_ERROR: i64 = -1;
pub const DB_TOO_MANY: i64 = -2;

/// SHA-256 en hexadecimal (64 caracteres) -> bytes.
pub fn hex_to_hash(text: &str) -> Option<[u8; HASH_LEN]> {
    let t = text.trim();
    if t.len() != HASH_LEN * 2 {
        return None;
    }
    let bytes = hex::decode(t).ok()?;
    let mut out = [0u8; HASH_LEN];
    out.copy_from_slice(&bytes);
    Some(out)
}

/// Busca en una línea (txt, csv con comillas, "hash  nombre"...) el primer token de
/// exactamente 64 caracteres hexadecimales. Ignora comentarios que empiezan con '#'.
pub fn find_hash_in_line(line: &str) -> Option<[u8; HASH_LEN]> {
    let trimmed = line.trim_start();
    if trimmed.is_empty() || trimmed.starts_with('#') {
        return None;
    }
    trimmed
        .split(|c: char| !c.is_ascii_alphanumeric())
        .find(|token| token.len() == HASH_LEN * 2 && token.bytes().all(|b| b.is_ascii_hexdigit()))
        .and_then(hex_to_hash)
}

fn read_db(path: &Path) -> Result<Vec<[u8; HASH_LEN]>, i64> {
    match std::fs::read(path) {
        Ok(bytes) => {
            if bytes.len() % HASH_LEN != 0 {
                return Err(DB_OK_IO_ERROR);
            }
            Ok(bytes
                .chunks_exact(HASH_LEN)
                .map(|c| {
                    let mut h = [0u8; HASH_LEN];
                    h.copy_from_slice(c);
                    h
                })
                .collect())
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(_) => Err(DB_OK_IO_ERROR),
    }
}

/// Agrega a la base los hashes de un archivo de texto. Devuelve la cantidad total
/// de hashes tras la fusión, o un código negativo (`DB_OK_IO_ERROR`, `DB_TOO_MANY`).
pub fn hashdb_merge_text(db_path: &Path, text_path: &Path, max_entries: usize) -> i64 {
    let mut hashes = match read_db(db_path) {
        Ok(h) => h,
        Err(code) => return code,
    };
    let input = match File::open(text_path) {
        Ok(f) => BufReader::new(f),
        Err(_) => return DB_OK_IO_ERROR,
    };
    let mut reader = input;
    let mut line = Vec::new();
    loop {
        line.clear();
        match reader.read_until(b'\n', &mut line) {
            Ok(0) => break,
            Ok(_) => {
                if let Some(h) = find_hash_in_line(&String::from_utf8_lossy(&line)) {
                    hashes.push(h);
                }
            }
            Err(_) => return DB_OK_IO_ERROR,
        }
    }
    hashes.sort_unstable();
    hashes.dedup();
    if hashes.len() > max_entries {
        return DB_TOO_MANY;
    }

    let tmp = db_path.with_extension("tmp");
    let write = || -> std::io::Result<()> {
        let mut out = File::create(&tmp)?;
        for h in &hashes {
            out.write_all(h)?;
        }
        out.sync_all()?;
        std::fs::rename(&tmp, db_path)
    };
    if write().is_err() {
        let _ = std::fs::remove_file(&tmp);
        return DB_OK_IO_ERROR;
    }
    hashes.len() as i64
}

pub fn hashdb_count(db_path: &Path) -> i64 {
    match std::fs::metadata(db_path) {
        Ok(m) => (m.len() / HASH_LEN as u64) as i64,
        Err(_) => 0,
    }
}

/// Búsqueda binaria directamente sobre el archivo (sin cargarlo en memoria).
pub fn hashdb_contains(db_path: &Path, hash: &[u8; HASH_LEN]) -> bool {
    let file = match File::open(db_path) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let len = match file.metadata() {
        Ok(m) => m.len(),
        Err(_) => return false,
    };
    let (mut lo, mut hi) = (0u64, len / HASH_LEN as u64);
    let mut buf = [0u8; HASH_LEN];
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        if file.read_exact_at(&mut buf, mid * HASH_LEN as u64).is_err() {
            return false;
        }
        match buf.cmp(hash) {
            std::cmp::Ordering::Equal => return true,
            std::cmp::Ordering::Less => lo = mid + 1,
            std::cmp::Ordering::Greater => hi = mid,
        }
    }
    false
}

pub fn sha256_of_path(path: &Path) -> Option<String> {
    sha256_of_file(File::open(path).ok()?)
}

// ---------------------------------------------------------------------------
// Capa JNI
// ---------------------------------------------------------------------------

fn read_jstring(env: &mut JNIEnv, value: &JString) -> Option<Zeroizing<String>> {
    let java_str = env.get_string(value).ok()?;
    let owned: String = java_str.into();
    Some(Zeroizing::new(owned))
}

fn to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => NULL_STR,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_generateHighEntropyPassword(
    mut env: JNIEnv,
    _class: JClass,
    length: jint,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let len = if length < 0 {
            MIN_PASSWORD_LEN
        } else {
            length as usize
        };
        match generate_password(len) {
            Some(password) => to_jstring(&mut env, password.as_str()),
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_encryptSecret(
    mut env: JNIEnv,
    _class: JClass,
    master_key: JString,
    plaintext: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let key = match read_jstring(&mut env, &master_key) {
            Some(k) => k,
            None => return NULL_STR,
        };
        let text = match read_jstring(&mut env, &plaintext) {
            Some(t) => t,
            None => return NULL_STR,
        };
        match encrypt_text(key.as_str(), text.as_str()) {
            Some(out) => to_jstring(&mut env, &out),
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_decryptSecret(
    mut env: JNIEnv,
    _class: JClass,
    master_key: JString,
    payload: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let key = match read_jstring(&mut env, &master_key) {
            Some(k) => k,
            None => return NULL_STR,
        };
        let data = match read_jstring(&mut env, &payload) {
            Some(d) => d,
            None => return NULL_STR,
        };
        match decrypt_text(key.as_str(), data.as_str()) {
            Some(plain) => {
                let plain = Zeroizing::new(plain);
                to_jstring(&mut env, plain.as_str())
            }
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

/// Calcula el SHA-256 de un descriptor de archivo.
/// Kotlin cede la propiedad del descriptor con `detachFd()`; aquí se cierra al terminar.
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_sha256OfFd(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        if fd < 0 {
            return NULL_STR;
        }
        // SAFETY: el llamador transfirió la propiedad del descriptor (detachFd);
        // `File` lo cierra al salir de `sha256_of_file`.
        let file = unsafe { File::from_raw_fd(fd) };
        match sha256_of_file(file) {
            Some(hash) => to_jstring(&mut env, &hash),
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

fn path_from(env: &mut JNIEnv, value: &JString) -> Option<std::path::PathBuf> {
    let java_str = env.get_string(value).ok()?;
    let owned: String = java_str.into();
    Some(std::path::PathBuf::from(owned))
}

fn to_jbytes(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// SHA-256 de un archivo por ruta (para recorrer el almacenamiento).
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_sha256OfPath(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let p = match path_from(&mut env, &path) {
            Some(p) => p,
            None => return NULL_STR,
        };
        match sha256_of_path(&p) {
            Some(hash) => to_jstring(&mut env, &hash),
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultCreate(
    mut env: JNIEnv,
    _class: JClass,
    password: JString,
    plaintext: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let pw = match read_jstring(&mut env, &password) {
            Some(v) => v,
            None => return NULL_STR,
        };
        let text = match read_jstring(&mut env, &plaintext) {
            Some(v) => v,
            None => return NULL_STR,
        };
        match vault_create(pw.as_str(), text.as_bytes()) {
            Some(blob) => to_jstring(&mut env, &blob),
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultOpen(
    mut env: JNIEnv,
    _class: JClass,
    password: JString,
    blob: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let pw = match read_jstring(&mut env, &password) {
            Some(v) => v,
            None => return NULL_STR,
        };
        let data = match read_jstring(&mut env, &blob) {
            Some(v) => v,
            None => return NULL_STR,
        };
        match vault_open(pw.as_str(), data.as_str()) {
            Some(plain) => match std::str::from_utf8(&plain) {
                Ok(text) => to_jstring(&mut env, text),
                Err(_) => NULL_STR,
            },
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultPeek(
    mut env: JNIEnv,
    _class: JClass,
    password: JString,
    blob: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let pw = match read_jstring(&mut env, &password) {
            Some(v) => v,
            None => return NULL_STR,
        };
        let data = match read_jstring(&mut env, &blob) {
            Some(v) => v,
            None => return NULL_STR,
        };
        match vault_peek(pw.as_str(), data.as_str()) {
            Some(plain) => match std::str::from_utf8(&plain) {
                Ok(text) => to_jstring(&mut env, text),
                Err(_) => NULL_STR,
            },
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultOpenWithKey(
    mut env: JNIEnv,
    _class: JClass,
    key: JByteArray,
    blob: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let key_bytes = match env.convert_byte_array(&key) {
            Ok(v) => Zeroizing::new(v),
            Err(_) => return NULL_STR,
        };
        let data = match read_jstring(&mut env, &blob) {
            Some(v) => v,
            None => return NULL_STR,
        };
        match vault_open_with_key(&key_bytes, data.as_str()) {
            Some(plain) => match std::str::from_utf8(&plain) {
                Ok(text) => to_jstring(&mut env, text),
                Err(_) => NULL_STR,
            },
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultSave(
    mut env: JNIEnv,
    _class: JClass,
    plaintext: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let text = match read_jstring(&mut env, &plaintext) {
            Some(v) => v,
            None => return NULL_STR,
        };
        match vault_save(text.as_bytes()) {
            Some(blob) => to_jstring(&mut env, &blob),
            None => NULL_STR,
        }
    }))
    .unwrap_or(NULL_STR)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultLock(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(vault_lock);
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultIsUnlocked(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    catch_unwind(|| if vault_is_unlocked() { 1 } else { 0 }).unwrap_or(0)
}

/// Devuelve la clave derivada de la sesión (para protegerla con la huella).
/// Kotlin debe borrar el arreglo apenas la use.
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_vaultExportKey(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| match vault_export_key() {
        Some(key) => to_jbytes(&mut env, &key),
        None => std::ptr::null_mut(),
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_hashDbMerge(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    text_path: JString,
    max_entries: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let db = match path_from(&mut env, &db_path) {
            Some(p) => p,
            None => return DB_OK_IO_ERROR,
        };
        let txt = match path_from(&mut env, &text_path) {
            Some(p) => p,
            None => return DB_OK_IO_ERROR,
        };
        let max = if max_entries < 0 { 0 } else { max_entries as usize };
        hashdb_merge_text(&db, &txt, max)
    }))
    .unwrap_or(DB_OK_IO_ERROR) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_hashDbContains(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    hash: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let db = match path_from(&mut env, &db_path) {
            Some(p) => p,
            None => return 0,
        };
        let h = match read_jstring(&mut env, &hash).and_then(|s| hex_to_hash(s.as_str())) {
            Some(h) => h,
            None => return 0,
        };
        if hashdb_contains(&db, &h) {
            1
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_hashDbCount(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| match path_from(&mut env, &db_path) {
        Some(p) => hashdb_count(&p),
        None => 0,
    }))
    .unwrap_or(0) as jlong
}

// ---------------------------------------------------------------------------
// Pruebas (cargo test --release)
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn roundtrip_con_unicode() {
        let enc = encrypt_text("correct horse battery", "mi secreto ñ 🔐").unwrap();
        assert!(enc.starts_with(PREFIX));
        let dec = decrypt_text("correct horse battery", &enc).unwrap();
        assert_eq!(dec, "mi secreto ñ 🔐");
    }

    #[test]
    fn el_salt_es_distinto_en_cada_cifrado() {
        let a = encrypt_text("clave-de-prueba-1", "x").unwrap();
        let b = encrypt_text("clave-de-prueba-1", "x").unwrap();
        assert_ne!(&a[PREFIX.len()..PREFIX.len() + SALT_LEN * 2], &b[PREFIX.len()..PREFIX.len() + SALT_LEN * 2]);
        assert_ne!(a, b);
    }

    #[test]
    fn contrasena_incorrecta_falla() {
        let enc = encrypt_text("la-correcta-123", "dato").unwrap();
        assert!(decrypt_text("la-incorrecta-123", &enc).is_none());
    }

    #[test]
    fn detecta_modificaciones() {
        let enc = encrypt_text("la-correcta-123", "dato").unwrap();
        let mut bytes = enc.into_bytes();
        let last = bytes.len() - 1;
        bytes[last] = if bytes[last] == b'0' { b'1' } else { b'0' };
        let tampered = String::from_utf8(bytes).unwrap();
        assert!(decrypt_text("la-correcta-123", &tampered).is_none());
    }

    #[test]
    fn rechaza_entradas_invalidas() {
        assert!(decrypt_text("x", "hola").is_none());
        assert!(decrypt_text("x", "vgx1:zz").is_none());
        assert!(decrypt_text("x", "vgx1:abcd").is_none());
        assert!(encrypt_text("", "dato").is_none());
    }

    #[test]
    fn contrasenas_tienen_largo_y_clases() {
        for len in [8usize, 12, 24, 64, 128] {
            let pw = generate_password(len).unwrap();
            assert_eq!(pw.len(), len);
            assert!(has_all_classes(pw.as_bytes()));
        }
        assert_eq!(generate_password(1).unwrap().len(), MIN_PASSWORD_LEN);
        assert_eq!(generate_password(100_000).unwrap().len(), MAX_PASSWORD_LEN);
    }

    #[test]
    fn sha256_vector_conocido() {
        let path = std::env::temp_dir().join("vigilix_sha256_test.bin");
        {
            let mut f = File::create(&path).unwrap();
            f.write_all(b"abc").unwrap();
        }
        let hash = sha256_of_file(File::open(&path).unwrap()).unwrap();
        let _ = std::fs::remove_file(&path);
        assert_eq!(
            hash,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    // --- Bóveda ---------------------------------------------------------

    // La sesión es global: las pruebas que la usan se ejecutan de a una.
    static VAULT_TEST_LOCK: Mutex<()> = Mutex::new(());

    fn vault_guard() -> MutexGuard<'static, ()> {
        VAULT_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    #[test]
    fn boveda_crear_guardar_y_reabrir() {
        let _g = vault_guard();
        let blob = vault_create("clave-maestra-1", br#"{"items":[]}"#).unwrap();
        assert!(blob.starts_with(VAULT_PREFIX));
        assert!(vault_is_unlocked());

        let blob2 = vault_save(br#"{"items":[{"u":"ana"}]}"#).unwrap();
        vault_lock();
        assert!(!vault_is_unlocked());

        let plain = vault_open("clave-maestra-1", &blob2).unwrap();
        assert_eq!(&plain[..], br#"{"items":[{"u":"ana"}]}"#);
        assert!(vault_is_unlocked());
        vault_lock();
    }

    #[test]
    fn boveda_clave_incorrecta_no_abre_ni_deja_sesion() {
        let _g = vault_guard();
        let blob = vault_create("clave-maestra-1", b"datos").unwrap();
        vault_lock();
        assert!(vault_open("clave-maestra-2", &blob).is_none());
        assert!(!vault_is_unlocked());
        assert!(vault_save(b"x").is_none());
    }

    #[test]
    fn boveda_detecta_modificaciones_y_formato() {
        let _g = vault_guard();
        let blob = vault_create("clave-maestra-1", b"datos").unwrap();
        vault_lock();
        let mut bytes = blob.clone().into_bytes();
        let i = bytes.len() / 2;
        bytes[i] = if bytes[i] == b'0' { b'1' } else { b'0' };
        let tampered = String::from_utf8(bytes).unwrap();
        assert!(vault_open("clave-maestra-1", &tampered).is_none());
        assert!(vault_open("clave-maestra-1", "vgv1:abcd").is_none());
        // Un texto cifrado normal (vgx1) no es una bóveda.
        let suelto = encrypt_text("clave-maestra-1", "datos").unwrap();
        assert!(vault_open("clave-maestra-1", &suelto).is_none());
        assert!(!vault_is_unlocked());
    }

    #[test]
    fn boveda_cada_guardado_usa_nonce_nuevo_y_mismo_salt() {
        let _g = vault_guard();
        let a = vault_create("clave-maestra-1", b"datos").unwrap();
        let b = vault_save(b"datos").unwrap();
        let c = vault_save(b"datos").unwrap();
        let salt = |s: &str| s[VAULT_PREFIX.len()..VAULT_PREFIX.len() + SALT_LEN * 2].to_string();
        let nonce = |s: &str| {
            s[VAULT_PREFIX.len() + SALT_LEN * 2..VAULT_PREFIX.len() + (SALT_LEN + NONCE_LEN) * 2].to_string()
        };
        assert_eq!(salt(&a), salt(&b));
        assert_eq!(salt(&b), salt(&c));
        assert_ne!(nonce(&b), nonce(&c));
        vault_lock();
    }

    #[test]
    fn boveda_abre_con_clave_exportada_para_la_huella() {
        let _g = vault_guard();
        let blob = vault_create("clave-maestra-1", b"secreto").unwrap();
        let key = vault_export_key().unwrap();
        assert_eq!(key.len(), KEY_LEN);
        vault_lock();
        assert!(vault_export_key().is_none());

        let plain = vault_open_with_key(&key, &blob).unwrap();
        assert_eq!(&plain[..], b"secreto");
        assert!(vault_save(b"otro").is_some());
        // Clave de largo incorrecto o equivocada: no abre.
        vault_lock();
        assert!(vault_open_with_key(&key[..16], &blob).is_none());
        let mut mala = key.to_vec();
        mala[0] ^= 1;
        assert!(vault_open_with_key(&mala, &blob).is_none());
        assert!(!vault_is_unlocked());
    }

    #[test]
    fn boveda_cambiar_clave_maestra() {
        let _g = vault_guard();
        let blob_a = vault_create("clave-vieja-123", b"datos").unwrap();
        let plain = vault_open("clave-vieja-123", &blob_a).unwrap();
        let blob_b = vault_create("clave-nueva-456", &plain).unwrap();
        vault_lock();
        assert!(vault_open("clave-vieja-123", &blob_b).is_none());
        assert_eq!(&vault_open("clave-nueva-456", &blob_b).unwrap()[..], b"datos");
        vault_lock();
    }

    #[test]
    fn boveda_peek_no_toca_la_sesion_abierta() {
        let _g = vault_guard();
        // Bóveda ajena (la que se importa).
        let ajena = vault_create("clave-ajena-999", b"datos ajenos").unwrap();
        // Bóveda propia, abierta.
        let propia = vault_create("clave-propia-111", b"datos propios").unwrap();
        assert!(vault_is_unlocked());

        let leido = vault_peek("clave-ajena-999", &ajena).unwrap();
        assert_eq!(&leido[..], b"datos ajenos");
        assert!(vault_peek("clave-propia-111", &ajena).is_none());

        // Guardar sigue usando la clave de la bóveda propia.
        let nuevo = vault_save(b"datos propios 2").unwrap();
        vault_lock();
        assert_eq!(&vault_open("clave-propia-111", &nuevo).unwrap()[..], b"datos propios 2");
        assert!(vault_open("clave-ajena-999", &nuevo).is_none());
        let _ = propia;
        vault_lock();
    }

    // --- Base de firmas -------------------------------------------------

    const EICAR: &str = "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f";
    const H_ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    const H_ZERO: &str = "0000000000000000000000000000000000000000000000000000000000000000";
    const H_FF: &str = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    fn tmp(name: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("vigilix_test_{}_{}", std::process::id(), name));
        let _ = std::fs::remove_file(&p);
        p
    }

    fn write_text(path: &Path, text: &str) {
        std::fs::write(path, text).unwrap();
    }

    #[test]
    fn extrae_hash_de_varios_formatos() {
        let esperado = hex_to_hash(EICAR).unwrap();
        assert_eq!(find_hash_in_line(EICAR), Some(esperado));
        assert_eq!(find_hash_in_line(&EICAR.to_uppercase()), Some(esperado));
        assert_eq!(find_hash_in_line(&format!("{}  eicar.com", EICAR)), Some(esperado));
        // CSV de MalwareBazaar: el SHA-256 va entre comillas y antes que md5/sha1.
        let csv = format!(
            "\"2026-10-01 10:00:00\", \"{}\", \"d41d8cd98f00b204e9800998ecf8427e\", \"da39a3ee5e6b4b0d3255bfef95601890afd80709\", \"reporter\", \"a.apk\"",
            EICAR
        );
        assert_eq!(find_hash_in_line(&csv), Some(esperado));
        assert_eq!(find_hash_in_line(&format!("{},Win32.Eicar", EICAR)), Some(esperado));
    }

    #[test]
    fn ignora_comentarios_y_tokens_que_no_son_sha256() {
        assert!(find_hash_in_line(&format!("# {}", EICAR)).is_none());
        assert!(find_hash_in_line("").is_none());
        assert!(find_hash_in_line("d41d8cd98f00b204e9800998ecf8427e").is_none()); // md5
        assert!(find_hash_in_line("da39a3ee5e6b4b0d3255bfef95601890afd80709").is_none()); // sha1
        assert!(find_hash_in_line(&format!("{}0", EICAR)).is_none()); // 65 caracteres
        assert!(find_hash_in_line(&"g".repeat(64)).is_none());
    }

    #[test]
    fn base_de_firmas_fusiona_ordena_y_busca() {
        let db = tmp("db1.vxdb");
        let txt = tmp("db1.txt");
        write_text(
            &txt,
            &format!("# lista\n{}\n{}\n{}\n{}\n{}\nbasura\n", H_FF, EICAR, H_ZERO, EICAR, H_ABC),
        );
        let n = hashdb_merge_text(&db, &txt, 100);
        assert_eq!(n, 4, "debe quitar el duplicado");
        assert_eq!(hashdb_count(&db), 4);

        for h in [H_FF, EICAR, H_ZERO, H_ABC] {
            assert!(hashdb_contains(&db, &hex_to_hash(h).unwrap()), "falta {}", h);
        }
        let otro = hex_to_hash(&"ab".repeat(32)).unwrap();
        assert!(!hashdb_contains(&db, &otro));

        // El archivo queda ordenado (requisito de la búsqueda binaria).
        let bytes = std::fs::read(&db).unwrap();
        let chunks: Vec<&[u8]> = bytes.chunks_exact(HASH_LEN).collect();
        assert!(chunks.windows(2).all(|w| w[0] < w[1]));

        let _ = std::fs::remove_file(&db);
        let _ = std::fs::remove_file(&txt);
    }

    #[test]
    fn base_de_firmas_acumula_entre_actualizaciones() {
        let db = tmp("db2.vxdb");
        let t1 = tmp("db2a.txt");
        let t2 = tmp("db2b.txt");
        write_text(&t1, &format!("{}\n{}\n", EICAR, H_ABC));
        write_text(&t2, &format!("{}\n{}\n", H_ABC, H_ZERO));
        assert_eq!(hashdb_merge_text(&db, &t1, 100), 2);
        assert_eq!(hashdb_merge_text(&db, &t2, 100), 3);
        assert!(hashdb_contains(&db, &hex_to_hash(EICAR).unwrap()));
        assert!(hashdb_contains(&db, &hex_to_hash(H_ZERO).unwrap()));
        for p in [&db, &t1, &t2] {
            let _ = std::fs::remove_file(p);
        }
    }

    #[test]
    fn base_de_firmas_respeta_el_maximo_y_no_la_toca() {
        let db = tmp("db3.vxdb");
        let t1 = tmp("db3a.txt");
        let t2 = tmp("db3b.txt");
        write_text(&t1, &format!("{}\n{}\n", EICAR, H_ABC));
        assert_eq!(hashdb_merge_text(&db, &t1, 2), 2);
        write_text(&t2, &format!("{}\n", H_ZERO));
        assert_eq!(hashdb_merge_text(&db, &t2, 2), DB_TOO_MANY);
        assert_eq!(hashdb_count(&db), 2, "no debe cambiar si se pasa del máximo");
        assert!(!hashdb_contains(&db, &hex_to_hash(H_ZERO).unwrap()));
        for p in [&db, &t1, &t2] {
            let _ = std::fs::remove_file(p);
        }
    }

    #[test]
    fn base_de_firmas_casos_de_error() {
        let db = tmp("db4.vxdb");
        let inexistente = tmp("db4_no_existe.txt");
        assert_eq!(hashdb_merge_text(&db, &inexistente, 10), DB_OK_IO_ERROR);
        assert_eq!(hashdb_count(&db), 0);
        assert!(!hashdb_contains(&db, &hex_to_hash(EICAR).unwrap()));

        // Base corrupta (largo que no es múltiplo de 32): se rechaza sin pisarla.
        std::fs::write(&db, [1u8; 33]).unwrap();
        let t = tmp("db4.txt");
        write_text(&t, &format!("{}\n", EICAR));
        assert_eq!(hashdb_merge_text(&db, &t, 10), DB_OK_IO_ERROR);
        assert_eq!(std::fs::read(&db).unwrap().len(), 33);
        for p in [&db, &t] {
            let _ = std::fs::remove_file(p);
        }
    }

    #[test]
    fn sha256_por_ruta_y_deteccion_de_eicar() {
        // La cadena EICAR estándar es un archivo de prueba inofensivo que todo antivirus detecta.
        let eicar = tmp("eicar.com");
        std::fs::write(
            &eicar,
            br"X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*",
        )
        .unwrap();
        let hash = sha256_of_path(&eicar).unwrap();
        assert_eq!(hash, EICAR);
        assert!(sha256_of_path(&tmp("no_existe.bin")).is_none());

        let db = tmp("db5.vxdb");
        let t = tmp("db5.txt");
        write_text(&t, &format!("{}\n", EICAR));
        assert_eq!(hashdb_merge_text(&db, &t, 10), 1);
        assert!(hashdb_contains(&db, &hex_to_hash(&hash).unwrap()));
        for p in [&eicar, &db, &t] {
            let _ = std::fs::remove_file(p);
        }
    }
}
