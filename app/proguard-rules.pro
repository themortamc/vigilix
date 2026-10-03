# JNI: el código nativo (Rust) localiza estos métodos por su nombre exacto
# (Java_com_vigilix_app_SecurityBridge_*). R8 no debe renombrarlos ni quitarlos.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.vigilix.app.SecurityBridge {
    private native <methods>;
}
