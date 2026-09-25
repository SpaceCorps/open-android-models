/* A minimal native host for JniProbeTest: OamJni.nativeDeliver appends "<handle>\t<line>\n" to $OAM_JNI_PROBE_OUT,
 * reading the line with GetStringUTFChars (JNI's modified UTF-8), as a naive C/Rust host would. */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int concurrent = 0, max_concurrent = 0;

JNIEXPORT void JNICALL Java_com_spacecorps_oam_jni_OamJni_nativeDeliver(JNIEnv *env, jclass cls, jlong handle, jstring line) {
    pthread_mutex_lock(&lock);
    if (++concurrent > max_concurrent) max_concurrent = concurrent;
    pthread_mutex_unlock(&lock);
    const char *path = getenv("OAM_JNI_PROBE_OUT");
    const char *utf = (*env)->GetStringUTFChars(env, line, NULL);
    FILE *out = fopen(path, "a");
    fprintf(out, "%lld\t%s\n", (long long)handle, utf);
    fclose(out);
    (*env)->ReleaseStringUTFChars(env, line, utf);
    pthread_mutex_lock(&lock);
    concurrent--;
    pthread_mutex_unlock(&lock);
}

/* Called by the probe to read the highest number of simultaneous deliveries seen. */
JNIEXPORT jint JNICALL Java_com_spacecorps_oam_jni_ProbeHost_maxConcurrent(JNIEnv *env, jclass cls) { return max_concurrent; }
