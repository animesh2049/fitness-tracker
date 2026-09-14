# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.animesh.workouttracker.**$$serializer { *; }
-keepclassmembers class com.animesh.workouttracker.** { *** Companion; }
-keepclasseswithmembers class com.animesh.workouttracker.** { kotlinx.serialization.KSerializer serializer(...); }
