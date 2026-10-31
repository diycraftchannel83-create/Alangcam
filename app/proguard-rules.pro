# Keep app-owned classes and members stable while R8 processes release dependencies.
# This avoids changing reflection-sensitive app entry points while still generating mapping.txt.
-keep class com.example.** { *; }

# Preserve runtime metadata used by Kotlin, Moshi, Room, and Android annotations.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Keep generated Moshi adapters that may be located by generated adapter naming conventions.
-keep class **JsonAdapter { *; }
