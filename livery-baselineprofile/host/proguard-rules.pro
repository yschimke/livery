# Kotlin serialization reads the bundled manifest; keep its generated serializers.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class ee.schimke.livery.**$$serializer { *; }
-keepclassmembers class ee.schimke.livery.** { *** Companion; }
-keepclasseswithmembers class ee.schimke.livery.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn **
