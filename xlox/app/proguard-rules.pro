# Native bridge: JNI resolves these classes and methods by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.xlox.BoxApplication { *; }
-keep class com.xlox.LogAct { *; }
-keep class com.xlox.MAct { *; }
-keep class com.xlox.utils.Downtwo { *; }

# Virtual-container SDK (prebuilt AAR): reflection and JNI throughout.
-keep class net_62v.** { *; }
-keep class top.bienvenido.** { *; }
-dontwarn net_62v.**
-dontwarn top.bienvenido.**

# zip4j optional crypto paths.
-dontwarn net.lingala.zip4j.**
