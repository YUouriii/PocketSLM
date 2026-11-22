-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.pocketllm.engine.LlamaBridge { *; }
-keep interface com.pocketllm.engine.TokenCallback { *; }
-keep class com.pocketllm.data.** { *; }
