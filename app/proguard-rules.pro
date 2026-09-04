# Keep NativeCode models and components accessed via reflection/JNI
-keep class com.zenithblue.nativecode.** { *; }

# Keep Termux X11 and its JNI bindings
-keep class com.termux.** { *; }

# Keep AIDL / IPC interfaces
-keep interface * extends android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }

# Compose rules
-keep class androidx.compose.material3.** { *; }

# Coroutines and Guava empty ListenFuture
-dontwarn com.google.common.util.concurrent.ListenableFuture

# Stub / hidden API classes used by termux-x11 CmdEntryPoint
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContextImpl
-dontwarn android.app.IActivityManager
-dontwarn android.content.IIntentReceiver$Stub
-dontwarn android.content.IIntentReceiver
-dontwarn android.content.IIntentSender
-dontwarn android.content.pm.IPackageManager
# Keep Preference classes parsed from XML
-keep class androidx.preference.** { *; }
-keepclassmembers class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context);
}
-keep class com.termux.x11.NcSwitchPreference { *; }

