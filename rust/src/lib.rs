use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

#[no_mangle]
pub extern "system" fn Java_com_vigilix_app_MainActivity_scanThreat(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input_str: String = env.get_string(&input).expect("Error de lectura").into();
    let lower = input_str.to_lowercase();

    // Reglas heurísticas de detección (Motor Vigilix Core)
    let analysis = if lower.contains("mercadopago") && !lower.contains("mercadopago.com.ar") {
        "🚨 PHISHING DETECTADO: Suplantación de identidad de Mercado Pago."
    } else if lower.contains("banco") || lower.contains("homebanking") || lower.contains("premio") || lower.contains("urgente") {
        "⚠️ ALERTA: Enlace sospechoso con palabras de ingeniería social."
    } else if lower.ends_with(".apk") || lower.ends_with(".exe") || lower.ends_with(".scr") {
        "🚨 ARCHIVO PELIGROSO: Descarga directa de ejecutable bloqueada."
    } else if lower.starts_with("+54") && (lower.contains("0000") || lower.ends_with("123")) {
        "⚠️ NÚMERO SOSPECHOSO: Patrón asociado a llamadas automatizadas (Spam)."
    } else {
        "✅ SEGURO: Sin patrones de amenaza detectados por Vigilix Core."
    };

    let output = env.new_string(analysis).expect("Error creando string de salida");
    output.into_raw()
}
