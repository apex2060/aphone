# pjsua2 calls back into these classes by JNI name. Release minify is off;
# these rules are here if it is turned on later.
-keep class org.pjsip.** { *; }
-keep class com.apex2060.softphone.SipEngine { *; }
-keep class com.apex2060.softphone.SipEngine$* { *; }
-dontwarn org.pjsip.**
-dontwarn javax.annotation.**
