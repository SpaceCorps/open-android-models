# Native hosts call OamJni through JNI (create/send/destroy) and implement its
# native nativeDeliver; R8 cannot see either use, so keep them by name.
# (Explicit return types: `***` does not match void.)
-keep class com.spacecorps.oam.jni.OamJni {
    public static long create(android.content.Context, long);
    public static void send(long, java.lang.String);
    public static void destroy(long);
    public static native void nativeDeliver(long, java.lang.String);
    public static com.spacecorps.oam.jni.MessageEngineFactory getEngineFactory();
    public static void setEngineFactory(com.spacecorps.oam.jni.MessageEngineFactory);
}
