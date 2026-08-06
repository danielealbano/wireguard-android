/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings);
extern void wgTurnOff(int handle);
extern void wgBumpSockets(int handle);
extern int wgGetSocketV4(int handle);
extern int wgGetSocketV6(int handle);
extern char *wgGetConfig(int handle);
extern char *wgVersion();

static pthread_mutex_t protect_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *protect_vm;
static jobject protect_obj;
static jmethodID protect_method;

/* wgAndroidProtectFd is called from the Go WebSocket bind's per-dial control hook (on an arbitrary
 * goroutine thread) to ask Java to VpnService.protect() a freshly created socket. It attaches to
 * the JVM if needed, invokes the cached protect(int) method, and returns whether it succeeded. */
int wgAndroidProtectFd(int fd)
{
	JNIEnv *env;
	bool attached = false;
	int ret = 0;

	pthread_mutex_lock(&protect_lock);
	if (!protect_vm || !protect_obj || !protect_method)
		goto out;
	switch ((*protect_vm)->GetEnv(protect_vm, (void **)&env, JNI_VERSION_1_6)) {
	case JNI_OK:
		break;
	case JNI_EDETACHED:
		if ((*protect_vm)->AttachCurrentThread(protect_vm, &env, NULL) != JNI_OK)
			goto out;
		attached = true;
		break;
	default:
		goto out;
	}
	ret = (*env)->CallBooleanMethod(env, protect_obj, protect_method, fd) == JNI_TRUE;
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		ret = 0;
	}
	if (attached)
		(*protect_vm)->DetachCurrentThread(protect_vm);
out:
	pthread_mutex_unlock(&protect_lock);
	return ret;
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings)
{
	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
	const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
	size_t settings_len = (*env)->GetStringUTFLength(env, settings);
	int ret = wgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	});
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	return ret;
}

JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	wgTurnOff(handle);
}

JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgSetFdProtector(JNIEnv *env, jclass c, jobject protector)
{
	pthread_mutex_lock(&protect_lock);
	if (protect_obj) {
		(*env)->DeleteGlobalRef(env, protect_obj);
		protect_obj = NULL;
		protect_method = NULL;
	}
	if (protector) {
		jclass cls = (*env)->GetObjectClass(env, protector);
		jmethodID method = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
		(*env)->DeleteLocalRef(env, cls);
		if (method) {
			(*env)->GetJavaVM(env, &protect_vm);
			protect_obj = (*env)->NewGlobalRef(env, protector);
			protect_method = method;
		}
	}
	pthread_mutex_unlock(&protect_lock);
}

JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgBumpSockets(JNIEnv *env, jclass c, jint handle)
{
	wgBumpSockets(handle);
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV4(JNIEnv *env, jclass c, jint handle)
{
	return wgGetSocketV4(handle);
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV6(JNIEnv *env, jclass c, jint handle)
{
	return wgGetSocketV6(handle);
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = wgGetConfig(handle);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = wgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}
