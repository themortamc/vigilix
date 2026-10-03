//! Vigilix core: criptografía y hashing para la app Android (vía JNI).
//!
//! Formato del texto cifrado (versión 1):
//!     "vgx1:" + hex( salt[16] || nonce[12] || ciphertext || tag[16] )
//!
//! - Clave   = Argon2id(contraseña maestra, salt aleatorio POR MENSAJE)
//! - Cifrado = ChaCha20-Poly1305, con "vigilix/v1" como dato autenticado (AAD)
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
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use rand::{rngs::OsRng, Rng, RngCore};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::Read;
use std::os::unix::io::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
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
}
