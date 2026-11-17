// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief ARCore head position as an xrt_tracked_slam, see arcore_tracker.h.
 */
#include "openview/arcore_tracker.h"
#include "arcore_convention.h"

#include "xrt/xrt_frame.h"
#include "xrt/xrt_tracking.h"
#include "os/os_threading.h"
#include "os/os_time.h"
#include "util/u_logging.h"
#include "util/u_misc.h"
#include "android/android_globals.h"

#include <jni.h>
#include <math.h>
#include <stdbool.h>
#include <string.h>

#define TRACKER_CLASS "dev.openview.runtime.tracking.ArCoreTracker"
//! A pose older than this means ARCore stopped delivering (camera lost, app paused).
#define OV_ARCORE_STALE_NS (300 * U_TIME_1MS_IN_NS)

struct ov_arcore
{
	struct xrt_tracked_slam base;
	struct xrt_frame_node node;
	jclass clazz; //!< Global reference to ArCoreTracker.

	struct os_mutex lock;
	struct ov_pose pose; //!< In the device's tracker convention.
	bool tracked;
	int64_t received_ns; //!< Monotonic time the last pose arrived.
};

//! One tracker per process: the Java side is a singleton and pushes into this.
static struct ov_arcore *g_tracker = NULL;
static struct os_mutex g_tracker_lock;
static bool g_tracker_lock_ready = false;

static void JNICALL
ov_arcore_push_pose(JNIEnv *env,
                    jclass clazz,
                    jfloat qx,
                    jfloat qy,
                    jfloat qz,
                    jfloat qw,
                    jfloat x,
                    jfloat y,
                    jfloat z,
                    jboolean tracked)
{
	(void)env;
	(void)clazz;
	struct ov_pose out = {{qx, qy, qz, qw}, {x, y, z}};
	bool finite = isfinite(qx) && isfinite(qy) && isfinite(qz) && isfinite(qw) && isfinite(x) && isfinite(y) &&
	              isfinite(z);

	os_mutex_lock(&g_tracker_lock);
	struct ov_arcore *t = g_tracker;
	if (t != NULL) {
		os_mutex_lock(&t->lock);
		t->tracked = tracked && finite;
		if (t->tracked) {
			t->pose = ov_output_to_slam(out);
		}
		t->received_ns = os_monotonic_get_ns();
		os_mutex_unlock(&t->lock);
	}
	os_mutex_unlock(&g_tracker_lock);
}

static void
ov_arcore_get_tracked_pose(struct xrt_tracked_slam *xts, timepoint_ns when_ns, struct xrt_space_relation *out)
{
	(void)when_ns; // The device asks for no prediction; it blends with its own gyro fusion.
	struct ov_arcore *t = (struct ov_arcore *)xts;
	struct xrt_space_relation rel = XRT_SPACE_RELATION_ZERO;

	os_mutex_lock(&t->lock);
	bool fresh = t->received_ns != 0 && os_monotonic_get_ns() - t->received_ns < OV_ARCORE_STALE_NS;
	if (t->tracked && fresh) {
		rel.pose.orientation = (struct xrt_quat){t->pose.orientation.x, t->pose.orientation.y,
		                                         t->pose.orientation.z, t->pose.orientation.w};
		rel.pose.position = (struct xrt_vec3){t->pose.position.x, t->pose.position.y, t->pose.position.z};
		rel.relation_flags = (enum xrt_space_relation_flags)(
		    XRT_SPACE_RELATION_ORIENTATION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT |
		    XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_POSITION_TRACKED_BIT);
	}
	os_mutex_unlock(&t->lock);

	*out = rel;
}

//! The calling thread's JNIEnv, attaching it when needed.
static JNIEnv *
ov_arcore_env(bool *attached)
{
	JavaVM *vm = (JavaVM *)android_globals_get_vm();
	*attached = false;
	if (vm == NULL) {
		return NULL;
	}
	JNIEnv *env = NULL;
	if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
		return env;
	}
	if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
		return NULL;
	}
	*attached = true;
	return env;
}

static void
ov_arcore_release_env(bool attached)
{
	if (attached) {
		JavaVM *vm = (JavaVM *)android_globals_get_vm();
		(*vm)->DetachCurrentThread(vm);
	}
}

static bool
ov_arcore_clear_exception(JNIEnv *env, const char *what)
{
	if (!(*env)->ExceptionCheck(env)) {
		return false;
	}
	(*env)->ExceptionDescribe(env);
	(*env)->ExceptionClear(env);
	U_LOG_W("ARCore: Java exception in %s", what);
	return true;
}

/*!
 * Loads ArCoreTracker with the application's class loader: a native thread
 * only sees the system classes through FindClass.
 */
static jclass
ov_arcore_load_class(JNIEnv *env, jobject context)
{
	jclass context_class = (*env)->GetObjectClass(env, context);
	jmethodID get_loader = (*env)->GetMethodID(env, context_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
	(*env)->DeleteLocalRef(env, context_class);
	if (get_loader == NULL || ov_arcore_clear_exception(env, "getClassLoader")) {
		return NULL;
	}
	jobject loader = (*env)->CallObjectMethod(env, context, get_loader);
	if (loader == NULL || ov_arcore_clear_exception(env, "getClassLoader()")) {
		return NULL;
	}
	jclass loader_class = (*env)->GetObjectClass(env, loader);
	jmethodID load = (*env)->GetMethodID(env, loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
	(*env)->DeleteLocalRef(env, loader_class);
	jstring name = (*env)->NewStringUTF(env, TRACKER_CLASS);
	jclass clazz = (jclass)(*env)->CallObjectMethod(env, loader, load, name);
	(*env)->DeleteLocalRef(env, name);
	(*env)->DeleteLocalRef(env, loader);
	if (clazz == NULL || ov_arcore_clear_exception(env, "loadClass")) {
		return NULL;
	}
	return clazz;
}

static void
ov_arcore_call_stop(struct ov_arcore *t)
{
	bool attached = false;
	JNIEnv *env = ov_arcore_env(&attached);
	if (env == NULL) {
		return;
	}
	jmethodID stop = (*env)->GetStaticMethodID(env, t->clazz, "stop", "()V");
	if (stop != NULL) {
		(*env)->CallStaticVoidMethod(env, t->clazz, stop);
	}
	ov_arcore_clear_exception(env, "stop");
	(*env)->DeleteGlobalRef(env, t->clazz);
	t->clazz = NULL;
	ov_arcore_release_env(attached);
}

static void
ov_arcore_break_apart(struct xrt_frame_node *node)
{
	struct ov_arcore *t = container_of(node, struct ov_arcore, node);
	os_mutex_lock(&g_tracker_lock);
	if (g_tracker == t) {
		g_tracker = NULL;
	}
	os_mutex_unlock(&g_tracker_lock);
	if (t->clazz != NULL) {
		ov_arcore_call_stop(t);
	}
}

static void
ov_arcore_destroy(struct xrt_frame_node *node)
{
	struct ov_arcore *t = container_of(node, struct ov_arcore, node);
	os_mutex_destroy(&t->lock);
	free(t);
}

int
ov_arcore_tracker_create(struct xrt_frame_context *xfctx, struct xrt_tracked_slam **out_xts)
{
	jobject context = (jobject)android_globals_get_context();
	bool attached = false;
	JNIEnv *env = ov_arcore_env(&attached);
	if (env == NULL || context == NULL) {
		U_LOG_W("ARCore: no Java VM or context in this process");
		ov_arcore_release_env(attached);
		return -1;
	}

	if (!g_tracker_lock_ready) {
		os_mutex_init(&g_tracker_lock);
		g_tracker_lock_ready = true;
	}

	int result = -1;
	struct ov_arcore *t = NULL;
	jmethodID start = NULL;
	jboolean started = JNI_FALSE;
	JNINativeMethod natives[] = {
	    {"nativePushPose", "(FFFFFFFZ)V", (void *)ov_arcore_push_pose},
	};
	jclass local = ov_arcore_load_class(env, context);
	if (local == NULL) {
		U_LOG_W("ARCore: %s is not in this runtime build", TRACKER_CLASS);
		goto out;
	}

	if ((*env)->RegisterNatives(env, local, natives, 1) != JNI_OK ||
	    ov_arcore_clear_exception(env, "RegisterNatives")) {
		U_LOG_W("ARCore: could not register the pose callback");
		(*env)->DeleteLocalRef(env, local);
		goto out;
	}

	t = U_TYPED_CALLOC(struct ov_arcore);
	os_mutex_init(&t->lock);
	t->base.get_tracked_pose = ov_arcore_get_tracked_pose;
	t->node.break_apart = ov_arcore_break_apart;
	t->node.destroy = ov_arcore_destroy;
	t->clazz = (jclass)(*env)->NewGlobalRef(env, local);
	(*env)->DeleteLocalRef(env, local);

	// Publish before starting: the first pose may arrive before start returns.
	os_mutex_lock(&g_tracker_lock);
	g_tracker = t;
	os_mutex_unlock(&g_tracker_lock);

	start = (*env)->GetStaticMethodID(env, t->clazz, "start", "(Landroid/content/Context;)Z");
	if (start != NULL && !ov_arcore_clear_exception(env, "GetStaticMethodID start")) {
		started = (*env)->CallStaticBooleanMethod(env, t->clazz, start, context);
		if (ov_arcore_clear_exception(env, "start")) {
			started = JNI_FALSE;
		}
	}
	if (!started) {
		U_LOG_I("ARCore: not available on this device");
		os_mutex_lock(&g_tracker_lock);
		g_tracker = NULL;
		os_mutex_unlock(&g_tracker_lock);
		(*env)->DeleteGlobalRef(env, t->clazz);
		os_mutex_destroy(&t->lock);
		free(t);
		goto out;
	}

	xrt_frame_context_add(xfctx, &t->node);
	*out_xts = &t->base;
	U_LOG_I("ARCore: head position from ARCore");
	result = 0;

out:
	ov_arcore_release_env(attached);
	return result;
}
