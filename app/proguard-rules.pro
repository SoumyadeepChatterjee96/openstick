# OpenStick Proguard Rules
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-keep class io.breadstick.x11.** { *; }
-keep class org.openstick.** { *; }
