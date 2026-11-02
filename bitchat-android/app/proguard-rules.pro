# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.bitchat.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.nostr.** { *; }
-keep class com.bitchat.android.identity.** { *; }

# Room loads generated database implementations by name and invokes their no-argument
# constructors reflectively. R8 full-mode can otherwise optimize away WorkDatabase_Impl's
# constructor, causing AndroidX Startup to crash before Application.onCreate.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# ML Kit discovers Firebase component registrars from manifest metadata and calls
# their no-argument constructors reflectively. Keeping the class alone does not
# retain that constructor in R8 full mode, leaving BarcodeScanning without its
# registered components and crashing when the verification scanner opens.
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# Keep Tor implementation (always included)
-keep class com.bitchat.android.net.RealTorProvider { *; }

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

# nemix: classes stored or sent as JSON with Gson. Their field names and generic types must
# survive R8, otherwise saved data breaks after every update (field names change) and lists
# come back as LinkedTreeMap (ClassCastException at startup when groups exist).
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.bitchat.android.groups.** { *; }
-keep class com.bitchat.android.contact.** { *; }
-keep class com.bitchat.android.directory.** { *; }
-keep class com.bitchat.android.migration.** { *; }
-keep class com.bitchat.android.security.** { *; }
-keep class com.bitchat.android.net.MeshInternetGatewayBridge { *; }
-keep class com.bitchat.android.net.MeshInternetGatewayBridge$* { *; }
