# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.campusmesh.android.protocol.** { *; }
-keep class com.campusmesh.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.campusmesh.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.campusmesh.android.favorites.** { *; }
-keep class com.campusmesh.android.nostr.** { *; }
-keep class com.campusmesh.android.identity.** { *; }

# Keep Tor implementation (always included)
-keep class com.campusmesh.android.net.RealTorProvider { *; }

# Arti (Custom Tor implementation in Rust) ProGuard rules
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.arti.** { *; }
-keepnames class org.torproject.arti.**
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.arti.**

# Fix for AbstractMethodError on API < 29 where LocationListener methods are abstract
-keepclassmembers class * implements android.location.LocationListener {
    public <methods>;
}

# Netty, Ktor, Log4j, Reactor, Jetty, and Java management dontwarn rules
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.slf4j.impl.**
-dontwarn java.lang.management.**


# Strip verbose/debug/info logging from release builds.
# ~900 Log.d/i/v calls ship in this app, several of which print message content, peer IDs and
# identity fingerprints. logcat is readable over adb and by OEM log collectors, so for a
# privacy-focused app these must not survive into release. Warnings and errors are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}
