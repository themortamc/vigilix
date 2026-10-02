use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jint};
use sha2::{Sha256, Digest};
use std::fs::File;
use std::io::Read;
use rand::Rng;
use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Nonce
};
use argon2::Argon2;

// --- 1. Generador de Contraseñas de Alta Entropía ---
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_generateHighEntropyPassword(
    mut env: JNIEnv,
    _class: JClass,
    length: jint,
) -> jstring {
    let len = if length < 8 { 16 } else { length as usize };
    const CHARSET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ\
                             abcdefghijklmnopqrstuvwxyz\
                             0123456789\
                             !@#$%^&*()_+-=[]{}|;:,.<>?";
    let mut rng = rand::thread_rng();
    let password: String = (0..len)
        .map(|_| {
            let idx = rng.gen_range(0..CHARSET.len());
            CHARSET[idx] as char
        })
        .collect();

    let output = env.new_string(password).expect("Error creando jstring");
    output.into_raw()
}

// --- 2. Cifrado de Bóveda: Argon2id + ChaCha20-Poly1305 ---
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_encryptSecret(
    mut env: JNIEnv,
    _class: JClass,
    master_key: JString,
    plaintext: JString,
) -> jstring {
    let key_str: String = env.get_string(&master_key).unwrap().into();
    let text_str: String = env.get_string(&plaintext).unwrap().into();

    let mut derived_key = [0u8; 32];
    let salt = b"VIGILIX_SALT_SECURE_2026";
    let argon2 = Argon2::default();
    
    if argon2.hash_password_into(key_str.as_bytes(), salt, &mut derived_key).is_err() {
        return env.new_string("ERROR_KDF").unwrap().into_raw();
    }

    let cipher = ChaCha20Poly1305::new_from_slice(&derived_key).unwrap();
    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    match cipher.encrypt(nonce, text_str.as_bytes()) {
        Ok(ciphertext) => {
            let mut combined = nonce_bytes.to_vec();
            combined.extend_from_slice(&ciphertext);
            env.new_string(hex::encode(combined)).unwrap().into_raw()
        },
        Err(_) => env.new_string("ERROR_ENCRYPTION").unwrap().into_raw()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_decryptSecret(
    mut env: JNIEnv,
    _class: JClass,
    master_key: JString,
    encrypted_hex: JString,
) -> jstring {
    let key_str: String = env.get_string(&master_key).unwrap().into();
    let hex_str: String = env.get_string(&encrypted_hex).unwrap().into();

    let data = match hex::decode(hex_str) {
        Ok(d) => d,
        Err(_) => return env.new_string("ERROR_INVALID_HEX").unwrap().into_raw()
    };

    if data.len() < 12 {
        return env.new_string("ERROR_PAYLOAD_TOO_SHORT").unwrap().into_raw();
    }

    let (nonce_bytes, ciphertext) = data.split_at(12);
    let mut derived_key = [0u8; 32];
    let salt = b"VIGILIX_SALT_SECURE_2026";
    let argon2 = Argon2::default();
    if argon2.hash_password_into(key_str.as_bytes(), salt, &mut derived_key).is_err() {
        return env.new_string("ERROR_KDF").unwrap().into_raw();
    }

    let cipher = ChaCha20Poly1305::new_from_slice(&derived_key).unwrap();
    let nonce = Nonce::from_slice(nonce_bytes);

    match cipher.decrypt(nonce, ciphertext) {
        Ok(plain) => {
            let decrypted = String::from_utf8_lossy(&plain).to_string();
            env.new_string(decrypted).unwrap().into_raw()
        },
        Err(_) => env.new_string("ERROR_INVALID_KEY_OR_TAMPERED").unwrap().into_raw()
    }
}

// --- 3. Escáner de Archivo SHA-256 (Estilo VirusTotal) ---
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_calculateFileSha256(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jstring {
    let path_str: String = env.get_string(&file_path).unwrap().into();
    let mut file = match File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return env.new_string("ERROR_OPEN_FILE").unwrap().into_raw()
    };

    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 8192];
    loop {
        let count = match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(c) => c,
            Err(_) => return env.new_string("ERROR_READING").unwrap().into_raw()
        };
        hasher.update(&buffer[..count]);
    }

    let result = hasher.finalize();
    let hash_hex = hex::encode(result);
    env.new_string(hash_hex).unwrap().into_raw()
}

// --- 4. Verificación de Amenazas Heurística/Hash ---
#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_SecurityBridge_scanThreat(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input_str: String = env.get_string(&input).expect("Error").into();
    let lower = input_str.to_lowercase();

    let analysis = if lower.contains("mercadopago") && !lower.contains("mercadopago.com.ar") {
        "🚨 PHISHING: Suplantación de identidad detectada."
    } else if lower.ends_with(".apk") || lower.ends_with(".dex") {
        "⚠️ ALERTA: Paquete ejecutable sospechoso. Requiere hash scan."
    } else {
        "✅ SEGURO: Sin patrones maliciosos evidentes."
    };

    env.new_string(analysis).unwrap().into_raw()
}
