# A Phone release R8 rules. proguard-android-optimize.txt plus library consumer rules
# (Firebase, AndroidX, CameraX, coroutines) cover most of the app. These rules keep
# everything that is reached by name from native code or by reflection.

# Readable crash stack traces after deobfuscation with mapping.txt.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-renamesourcefileattribute SourceFile

# ---- PJSIP / pjsua2 (SWIG + JNI) -------------------------------------------------
# libpjsua2.so looks up pjsua2JNI's SwigDirector_* callbacks and the proxy classes
# (swigCPtr fields, constructors) by name. Keep the whole generated package unrenamed.
-keep class org.pjsip.** { *; }
-keep interface org.pjsip.** { *; }
-keepclassmembers enum org.pjsip.** { *; }

# SWIG directors decide which callbacks are overridden with GetMethodID on the
# subclass (onRegState, onIncomingCall, onCallState, onCallMediaState, write, ...).
# Keep every app subclass of a pjsua2 class with its member names intact.
-keep class * extends org.pjsip.pjsua2.** { *; }

# That covers SipEngine's nested SpikeEndpoint, SpikeAccount, SpikeCall and RedactingLog.
# SipEngine itself is only called from Kotlin, so it is obfuscated normally.

# Native method implemented in libpjsua2.so (Java_com_twentytwo_phone_sipspike_AddrOverride_set).
-keep class com.twentytwo.phone.sipspike.AddrOverride { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---- Android components ----------------------------------------------------------
# AAPT keeps manifest components already; these are explicit so a manifest rename or
# a reflective lookup (Firebase MESSAGING_EVENT, foreground service, PendingIntents)
# never fails at runtime.
-keep class com.apex2060.softphone.SoftphoneApp { <init>(); }
-keep class com.apex2060.softphone.phone.PhoneService { <init>(); }
-keep class com.apex2060.softphone.phone.RecordingActionReceiver { <init>(); }
-keep class com.apex2060.softphone.push.PushMessagingService { <init>(); }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { <init>(); }
-keep class * extends android.telecom.ConnectionService { <init>(); }
-keep class * extends android.telecom.InCallService { <init>(); }

# ---- Data types -----------------------------------------------------------------
# Enums are persisted by name (CallHistory stores Direction/Outcome names and reads
# them back with valueOf). R8 keeps name() strings, but keep valueOf/values too.
-keepclassmembers enum com.apex2060.softphone.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelables (none today; kept in case one is added for intents/saved state).
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# WebView bridges (none today).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn org.pjsip.**
-dontwarn javax.annotation.**
