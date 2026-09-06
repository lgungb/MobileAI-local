# Keep LiteRT classes (JNI/反射相关)
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.android.gms.tflite.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep model/data classes (序列化)
-keep class com.google.ai.edge.gallery.data.** { *; }

# Keep Application class
-keep class com.google.ai.edge.gallery.GalleryApplication { *; }
