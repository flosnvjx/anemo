-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-repackageclasses
-allowaccessmodification
-overloadaggressively
