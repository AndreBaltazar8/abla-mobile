#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define ABLA_LOG_TAG "AblaMobile"
#define ABLA_TREE_LIMIT (1024U * 1024U)
#define ABLA_EVENT_LIMIT 64U
#define ABLA_PAYLOAD_LIMIT 4096U

#define ABLA_TREE_BEGIN 1
#define ABLA_TREE_END 2
#define ABLA_WAIT_EVENT 3
#define ABLA_EVENT_REVISION 4
#define ABLA_EVENT_PAYLOAD_SIZE 5
#define ABLA_EVENT_PAYLOAD_BYTE 6
#define ABLA_TREE_BYTE_BASE 256

typedef int64_t (*AblaHostCallback)(void *context, int64_t value);
typedef int64_t (*AblaMobileRun)(AblaHostCallback callback, void *context);

typedef struct AblaMobileEvent {
    int64_t revision;
    int64_t identifier;
    uint8_t payload[ABLA_PAYLOAD_LIMIT];
    size_t payload_size;
} AblaMobileEvent;

typedef struct AblaMobileBridge {
    pthread_mutex_t mutex;
    pthread_cond_t event_available;
    AblaMobileEvent events[ABLA_EVENT_LIMIT];
    size_t event_head;
    size_t event_size;
    AblaMobileEvent current_event;
    size_t current_payload_offset;
    uint8_t *tree;
    size_t tree_size;
    size_t tree_capacity;
    bool started;
    JavaVM *vm;
    jclass bridge_class;
    jmethodID tree_method;
} AblaMobileBridge;

static AblaMobileBridge abla_bridge = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .event_available = PTHREAD_COND_INITIALIZER,
};

static void abla_log_error(const char *message) {
    __android_log_write(ANDROID_LOG_ERROR, ABLA_LOG_TAG, message);
}

static bool abla_tree_append(uint8_t byte) {
    if (abla_bridge.tree_size >= ABLA_TREE_LIMIT) return false;
    if (abla_bridge.tree_size == abla_bridge.tree_capacity) {
        size_t next = abla_bridge.tree_capacity == 0
            ? 4096U
            : abla_bridge.tree_capacity * 2U;
        if (next > ABLA_TREE_LIMIT) next = ABLA_TREE_LIMIT;
        uint8_t *resized = (uint8_t *)realloc(abla_bridge.tree, next);
        if (resized == NULL) return false;
        abla_bridge.tree = resized;
        abla_bridge.tree_capacity = next;
    }
    abla_bridge.tree[abla_bridge.tree_size++] = byte;
    return true;
}

static void abla_publish_tree(void) {
    JNIEnv *environment = NULL;
    bool attached = false;
    const jint state = (*abla_bridge.vm)->GetEnv(
        abla_bridge.vm,
        (void **)&environment,
        JNI_VERSION_1_6
    );
    if (state == JNI_EDETACHED) {
        if ((*abla_bridge.vm)->AttachCurrentThread(
            abla_bridge.vm,
            &environment,
            NULL
        ) != JNI_OK) {
            abla_log_error("could not attach Abla mobile thread to JVM");
            return;
        }
        attached = true;
    } else if (state != JNI_OK) {
        abla_log_error("could not obtain JNIEnv for Abla mobile thread");
        return;
    }

    jbyteArray bytes = (*environment)->NewByteArray(
        environment,
        (jsize)abla_bridge.tree_size
    );
    if (bytes != NULL && abla_bridge.tree_size > 0) {
        (*environment)->SetByteArrayRegion(
            environment,
            bytes,
            0,
            (jsize)abla_bridge.tree_size,
            (const jbyte *)abla_bridge.tree
        );
    }
    if (bytes != NULL) {
        (*environment)->CallStaticVoidMethod(
            environment,
            abla_bridge.bridge_class,
            abla_bridge.tree_method,
            bytes
        );
        (*environment)->DeleteLocalRef(environment, bytes);
    }
    if ((*environment)->ExceptionCheck(environment)) {
        (*environment)->ExceptionDescribe(environment);
        (*environment)->ExceptionClear(environment);
        abla_log_error("Kotlin rejected an Abla UI tree");
    }
    if (attached) {
        (*abla_bridge.vm)->DetachCurrentThread(abla_bridge.vm);
    }
}

static int64_t abla_host_callback(void *context, int64_t value) {
    (void)context;
    if (value == ABLA_TREE_BEGIN) {
        abla_bridge.tree_size = 0;
        return 0;
    }
    if (value >= ABLA_TREE_BYTE_BASE &&
        value <= ABLA_TREE_BYTE_BASE + 255) {
        if (!abla_tree_append((uint8_t)(value - ABLA_TREE_BYTE_BASE))) {
            abla_log_error("Abla UI tree exceeded its native byte limit");
            return -1;
        }
        return 0;
    }
    if (value == ABLA_TREE_END) {
        abla_publish_tree();
        return 0;
    }
    if (value == ABLA_WAIT_EVENT) {
        pthread_mutex_lock(&abla_bridge.mutex);
        while (abla_bridge.event_size == 0) {
            pthread_cond_wait(
                &abla_bridge.event_available,
                &abla_bridge.mutex
            );
        }
        abla_bridge.current_event =
            abla_bridge.events[abla_bridge.event_head];
        abla_bridge.event_head =
            (abla_bridge.event_head + 1U) % ABLA_EVENT_LIMIT;
        abla_bridge.event_size -= 1U;
        abla_bridge.current_payload_offset = 0;
        pthread_mutex_unlock(&abla_bridge.mutex);
        return abla_bridge.current_event.identifier;
    }
    if (value == ABLA_EVENT_REVISION) {
        return abla_bridge.current_event.revision;
    }
    if (value == ABLA_EVENT_PAYLOAD_SIZE) {
        return (int64_t)abla_bridge.current_event.payload_size;
    }
    if (value == ABLA_EVENT_PAYLOAD_BYTE) {
        if (abla_bridge.current_payload_offset >=
            abla_bridge.current_event.payload_size) return 0;
        return abla_bridge.current_event.payload[
            abla_bridge.current_payload_offset++
        ];
    }
    return 0;
}

static void *abla_mobile_thread(void *unused) {
    (void)unused;
    void *library = dlopen("libabla_app.so", RTLD_NOW | RTLD_LOCAL);
    if (library == NULL) {
        abla_log_error(dlerror());
        return NULL;
    }
    AblaMobileRun run = (AblaMobileRun)dlsym(library, "abla_mobile_run");
    if (run == NULL) {
        abla_log_error("libabla_app.so does not export abla_mobile_run");
        dlclose(library);
        return NULL;
    }
    (void)run(abla_host_callback, &abla_bridge);
    dlclose(library);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_org_abla_mobile_host_NativeBridge_start(
    JNIEnv *environment,
    jobject receiver
) {
    pthread_mutex_lock(&abla_bridge.mutex);
    if (abla_bridge.started) {
        pthread_mutex_unlock(&abla_bridge.mutex);
        return JNI_TRUE;
    }

    jclass local_class = (*environment)->GetObjectClass(environment, receiver);
    abla_bridge.bridge_class = (jclass)(*environment)->NewGlobalRef(
        environment,
        local_class
    );
    (*environment)->DeleteLocalRef(environment, local_class);
    if (abla_bridge.bridge_class == NULL) {
        pthread_mutex_unlock(&abla_bridge.mutex);
        return JNI_FALSE;
    }
    abla_bridge.tree_method = (*environment)->GetStaticMethodID(
        environment,
        abla_bridge.bridge_class,
        "onTreeFromNative",
        "([B)V"
    );
    if (abla_bridge.tree_method == NULL) {
        pthread_mutex_unlock(&abla_bridge.mutex);
        return JNI_FALSE;
    }

    pthread_t thread;
    if (pthread_create(&thread, NULL, abla_mobile_thread, NULL) != 0) {
        pthread_mutex_unlock(&abla_bridge.mutex);
        return JNI_FALSE;
    }
    pthread_detach(thread);
    abla_bridge.started = true;
    pthread_mutex_unlock(&abla_bridge.mutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_abla_mobile_host_NativeBridge_sendEvent(
    JNIEnv *environment,
    jobject receiver,
    jlong revision,
    jlong identifier,
    jbyteArray payload
) {
    (void)receiver;
    const jsize payload_size = payload == NULL
        ? 0
        : (*environment)->GetArrayLength(environment, payload);
    if (payload_size < 0 || (size_t)payload_size > ABLA_PAYLOAD_LIMIT) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&abla_bridge.mutex);
    if (!abla_bridge.started || abla_bridge.event_size >= ABLA_EVENT_LIMIT) {
        pthread_mutex_unlock(&abla_bridge.mutex);
        return JNI_FALSE;
    }
    const size_t tail = (abla_bridge.event_head + abla_bridge.event_size) %
        ABLA_EVENT_LIMIT;
    AblaMobileEvent *event = &abla_bridge.events[tail];
    event->revision = (int64_t)revision;
    event->identifier = (int64_t)identifier;
    event->payload_size = (size_t)payload_size;
    if (payload_size > 0) {
        (*environment)->GetByteArrayRegion(
            environment,
            payload,
            0,
            payload_size,
            (jbyte *)event->payload
        );
    }
    abla_bridge.event_size += 1U;
    pthread_cond_signal(&abla_bridge.event_available);
    pthread_mutex_unlock(&abla_bridge.mutex);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    abla_bridge.vm = vm;
    return JNI_VERSION_1_6;
}

