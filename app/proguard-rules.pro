# Plivo SDK and its bundled WebRTC use JNI and reflection.
-keep class com.plivo.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn com.plivo.**
-dontwarn org.webrtc.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
