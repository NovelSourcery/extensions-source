#-dontobfuscate
#-dontoptimize

## Partially based on https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/build-system/gradle-core/src/main/resources/com/android/build/gradle/proguard-common.txt

# For enumeration classes, see https://www.guardsquare.com/manual/configuration/examples#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve annotated Javascript interface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

## Below are some of the custom rules for this repo

# Injekt — generic type tokens are captured via subclasses of FullTypeReference and
# resolved with reflection at runtime, so the Signature attribute is needed.
# https://r8.googlesource.com/r8/+/refs/heads/master/compatibility-faq.md#troubleshooting-gson-gson
-keepattributes Signature
-keep,allowshrinking,allowoptimization,allowobfuscation class * extends uy.kohesive.injekt.api.FullTypeReference

# kotlinx-serialization — runtime keeps required for @Serializable types and their
# generated $serializer companions.
# https://github.com/Kotlin/kotlinx.serialization/tree/dev/rules
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}

-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization,allowobfuscation class <1>

# NovelSource's members have no caller inside a single extension module - the host app calls
# them virtually through its own Source interface after the module is built, so R8 sees them as
# unreachable and strips them, silently defaulting isNovelSource to false / breaking fetchPageText.
-keepclassmembers class * implements eu.kanade.tachiyomi.source.NovelSource {
    public boolean isNovelSource();
    public java.lang.Object fetchPageText(eu.kanade.tachiyomi.source.model.Page, kotlin.coroutines.Continuation);
}
