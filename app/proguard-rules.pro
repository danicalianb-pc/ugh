-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.bella.ugh.WebAppBridge { *; }
-keep class com.bella.ugh.Config { *; }
