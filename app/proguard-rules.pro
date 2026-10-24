# youtubedl-android lê o JSON do yt-dlp com Jackson via reflexão.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# Commons Compress (descompacta Python/ffmpeg) registra os ZipExtraField por reflexão;
# sem isto o R8 os otimiza e o init quebra com "class ... is not a concrete class".
-keep class org.apache.commons.compress.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
