# JNI entry points are looked up by name.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.tdeletto.mp3bulk.encoder.LameEncoder { *; }
