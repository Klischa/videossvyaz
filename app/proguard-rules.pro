# WebRTC содержит нативные библиотеки; классы org.webrtc.* не должны удаляться.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
