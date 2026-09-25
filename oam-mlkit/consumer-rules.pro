# Native hosts call OamJni through JNI (create/send/destroy) and implement its
# native nativeDeliver; R8 cannot see either use, so keep the class and members.
-keep class com.spacecorps.oam.jni.OamJni {
    public static *** create(android.content.Context, long);
    public static *** send(long, java.lang.String);
    public static *** destroy(long);
    public static native <methods>;
    public static *** getEngineFactory();
    public static *** setEngineFactory(com.spacecorps.oam.jni.MessageEngineFactory);
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
