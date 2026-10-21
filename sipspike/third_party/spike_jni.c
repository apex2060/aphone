#include <jni.h>
#include <pj/addr_resolv.h>
#include <pj/errno.h>
#include <pj/string.h>

JNIEXPORT jboolean JNICALL
Java_com_twentytwo_phone_sipspike_AddrOverride_set(
        JNIEnv *env, jclass clazz, jstring host, jstring literal)
{
    const char *h = NULL;
    const char *a = NULL;
    pj_sockaddr addr;
    pj_str_t astr;
    pj_status_t st = PJ_SUCCESS;

    (void) clazz;

    if (host == NULL || literal == NULL) {
        pj_set_getaddrinfo_override(NULL, NULL);
        return JNI_TRUE;
    }

    h = (*env)->GetStringUTFChars(env, host, NULL);
    a = (*env)->GetStringUTFChars(env, literal, NULL);
    if (h == NULL || a == NULL || h[0] == '\0' || a[0] == '\0') {
        pj_set_getaddrinfo_override(NULL, NULL);
        if (h) (*env)->ReleaseStringUTFChars(env, host, h);
        if (a) (*env)->ReleaseStringUTFChars(env, literal, a);
        return JNI_TRUE;
    }

    pj_cstr(&astr, a);
    st = pj_sockaddr_parse(pj_AF_UNSPEC(), 0, &astr, &addr);
    if (st == PJ_SUCCESS)
        st = pj_set_getaddrinfo_override(h, &addr);
    else
        pj_set_getaddrinfo_override(NULL, NULL);

    (*env)->ReleaseStringUTFChars(env, host, h);
    (*env)->ReleaseStringUTFChars(env, literal, a);
    return st == PJ_SUCCESS ? JNI_TRUE : JNI_FALSE;
}
