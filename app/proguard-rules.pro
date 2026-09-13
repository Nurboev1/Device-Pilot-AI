# Proguard / R8 configuration for DevicePilot AI

# Keep JNI entry points and methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.devicepilot.ai.llm.LlamaEngine** { *; }

# Keep Shizuku classes
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
