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
#define ABLA_REQUEST_METHOD_LIMIT 8U
#define ABLA_REQUEST_URL_LIMIT 2048U
#define ABLA_REQUEST_BODY_LIMIT 16384U

#define ABLA_TREE_BEGIN 1
#define ABLA_TREE_END 2
#define ABLA_WAIT_EVENT 3
#define ABLA_EVENT_REVISION 4
#define ABLA_EVENT_PAYLOAD_SIZE 5
#define ABLA_EVENT_PAYLOAD_BYTE 6
#define ABLA_TREE_BYTE_BASE 256
#define ABLA_EFFECT_KIND_LIMIT 4
#define ABLA_FAILURE_STARTUP 1
#define ABLA_FAILURE_PROTOCOL 2
#define ABLA_FAILURE_RUNTIME_PANIC 3
#define ABLA_FAILURE_UNEXPECTED_RETURN 4

typedef int64_t (*AblaHostCallback)(void *context, int64_t value);
typedef int64_t (*AblaMobileRun)(void);
typedef int64_t (*AblaFailureByte)(int64_t index);
typedef int64_t (*AblaPlatformAttach)(
    AblaHostCallback callback,
    void *context
);
typedef int64_t (*AblaEffectNext)(void);
typedef int64_t (*AblaEffectByte)(int64_t index);
typedef void (*AblaEffectPop)(void);
typedef int64_t (*AblaRequestNext)(void);
typedef int64_t (*AblaRequestByte)(int64_t index);
typedef void (*AblaRequestPop)(void);

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
    bool tree_valid;
    uint8_t effect[ABLA_PAYLOAD_LIMIT];
    size_t effect_size;
    int effect_kind;
    AblaEffectNext effect_next_kind;
    AblaEffectNext effect_next_size;
    AblaEffectByte effect_next_byte;
    AblaEffectPop effect_pop;
    uint8_t request_method_bytes[ABLA_REQUEST_METHOD_LIMIT];
    uint8_t request_url[ABLA_REQUEST_URL_LIMIT];
    uint8_t request_body[ABLA_REQUEST_BODY_LIMIT];
    AblaRequestNext request_next_id;
    AblaRequestNext request_method_size;
    AblaRequestByte request_method_byte;
    AblaRequestNext request_url_size;
    AblaRequestByte request_url_byte;
    AblaRequestNext request_body_size;
    AblaRequestByte request_body_byte;
    AblaRequestPop request_pop;
    bool started;
    bool accepting_events;
    JavaVM *vm;
    jclass bridge_class;
    jmethodID tree_method;
    jmethodID effect_method;
    jmethodID request_callback_method;
    jmethodID failure_method;
} AblaMobileBridge;

static AblaMobileBridge abla_bridge = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .event_available = PTHREAD_COND_INITIALIZER,
};

static void abla_log_error(const char *message) {
    __android_log_write(ANDROID_LOG_ERROR, ABLA_LOG_TAG, message);
}

static JNIEnv *abla_jni_environment(bool *attached) {
    JNIEnv *environment = NULL;
    *attached = false;
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
        ) != JNI_OK) return NULL;
        *attached = true;
    } else if (state != JNI_OK) return NULL;
    return environment;
}

static jbyteArray abla_jni_bytes(
    JNIEnv *environment,
    const uint8_t *bytes,
    size_t size
) {
    if (size > (size_t)INT32_MAX) return NULL;
    jbyteArray result = (*environment)->NewByteArray(environment, (jsize)size);
    if (result != NULL && size > 0) {
        (*environment)->SetByteArrayRegion(
            environment,
            result,
            0,
            (jsize)size,
            (const jbyte *)bytes
        );
    }
    return result;
}

static void abla_finish_jni_call(JNIEnv *environment, bool attached) {
    if ((*environment)->ExceptionCheck(environment)) {
        (*environment)->ExceptionDescribe(environment);
        (*environment)->ExceptionClear(environment);
        abla_log_error("Kotlin rejected a native Abla callback");
    }
    if (attached) (*abla_bridge.vm)->DetachCurrentThread(abla_bridge.vm);
}

static void abla_publish_failure(int kind, const char *message) {
    abla_log_error(message);
    bool attached = false;
    JNIEnv *environment = abla_jni_environment(&attached);
    if (environment == NULL || abla_bridge.failure_method == NULL) return;
    const size_t length = strnlen(message, 1024U);
    jbyteArray bytes = abla_jni_bytes(
        environment,
        (const uint8_t *)message,
        length
    );
    if (bytes != NULL) {
        (*environment)->CallStaticVoidMethod(
            environment,
            abla_bridge.bridge_class,
            abla_bridge.failure_method,
            (jint)kind,
            bytes
        );
        (*environment)->DeleteLocalRef(environment, bytes);
    }
    abla_finish_jni_call(environment, attached);
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
    bool attached = false;
    JNIEnv *environment = abla_jni_environment(&attached);
    if (environment == NULL) {
        abla_log_error("could not obtain JNIEnv for Abla mobile thread");
        return;
    }

    jbyteArray bytes = abla_jni_bytes(
        environment,
        abla_bridge.tree,
        abla_bridge.tree_size
    );
    if (bytes != NULL) {
        (*environment)->CallStaticVoidMethod(
            environment,
            abla_bridge.bridge_class,
            abla_bridge.tree_method,
            bytes
        );
        (*environment)->DeleteLocalRef(environment, bytes);
    }
    abla_finish_jni_call(environment, attached);
}

static void abla_publish_effect(void) {
    __android_log_print(
        ANDROID_LOG_DEBUG,
        ABLA_LOG_TAG,
        "Abla effect %d (%zu bytes)",
        abla_bridge.effect_kind,
        abla_bridge.effect_size
    );
    bool attached = false;
    JNIEnv *environment = abla_jni_environment(&attached);
    if (environment == NULL) {
        abla_log_error("could not obtain JNIEnv for an Abla effect");
        return;
    }
    jbyteArray bytes = abla_jni_bytes(
        environment,
        abla_bridge.effect,
        abla_bridge.effect_size
    );
    if (bytes != NULL) {
        (*environment)->CallStaticVoidMethod(
            environment,
            abla_bridge.bridge_class,
            abla_bridge.effect_method,
            (jint)abla_bridge.effect_kind,
            bytes
        );
        (*environment)->DeleteLocalRef(environment, bytes);
    }
    abla_finish_jni_call(environment, attached);
}

static void abla_drain_platform_effects(void) {
    if (abla_bridge.effect_next_kind == NULL) return;
    int64_t kind = abla_bridge.effect_next_kind();
    while (kind != 0) {
        const int64_t size = abla_bridge.effect_next_size();
        if (kind < 1 || kind > ABLA_EFFECT_KIND_LIMIT || size < 0 ||
            size > ABLA_PAYLOAD_LIMIT) {
            abla_bridge.effect_pop();
            abla_publish_failure(
                ABLA_FAILURE_PROTOCOL,
                "libabla_app.so produced an invalid effect"
            );
            return;
        }
        abla_bridge.effect_kind = (int)kind;
        abla_bridge.effect_size = (size_t)size;
        for (int64_t index = 0; index < size; ++index) {
            const int64_t byte = abla_bridge.effect_next_byte(index);
            if (byte < 0 || byte > 255) {
                abla_bridge.effect_pop();
                abla_publish_failure(
                    ABLA_FAILURE_PROTOCOL,
                    "libabla_app.so produced an invalid effect payload"
                );
                return;
            }
            abla_bridge.effect[index] = (uint8_t)byte;
        }
        abla_bridge.effect_pop();
        abla_publish_effect();
        kind = abla_bridge.effect_next_kind();
    }
}

static bool abla_copy_platform_request_field(
    uint8_t *output,
    int64_t size,
    size_t limit,
    AblaRequestByte byte_at
) {
    if (size < 0 || (uint64_t)size > limit) return false;
    for (int64_t index = 0; index < size; ++index) {
        const int64_t byte = byte_at(index);
        if (byte < 0 || byte > 255) return false;
        output[index] = (uint8_t)byte;
    }
    return true;
}

static void abla_publish_request(
    int64_t identifier,
    int64_t method_size,
    int64_t url_size,
    int64_t body_size
) {
    bool attached = false;
    JNIEnv *environment = abla_jni_environment(&attached);
    if (environment == NULL) {
        abla_log_error("could not obtain JNIEnv for an Abla HTTP request");
        return;
    }
    jbyteArray method = abla_jni_bytes(
        environment, abla_bridge.request_method_bytes, (size_t)method_size
    );
    jbyteArray url = abla_jni_bytes(
        environment, abla_bridge.request_url, (size_t)url_size
    );
    jbyteArray body = abla_jni_bytes(
        environment, abla_bridge.request_body, (size_t)body_size
    );
    if (method != NULL && url != NULL && body != NULL) {
        (*environment)->CallStaticVoidMethod(
            environment,
            abla_bridge.bridge_class,
            abla_bridge.request_callback_method,
            (jlong)identifier,
            method,
            url,
            body
        );
    }
    if (method != NULL) (*environment)->DeleteLocalRef(environment, method);
    if (url != NULL) (*environment)->DeleteLocalRef(environment, url);
    if (body != NULL) (*environment)->DeleteLocalRef(environment, body);
    abla_finish_jni_call(environment, attached);
}

static void abla_drain_platform_requests(void) {
    if (abla_bridge.request_next_id == NULL) return;
    int64_t identifier = abla_bridge.request_next_id();
    while (identifier != 0) {
        const int64_t method_size = abla_bridge.request_method_size();
        const int64_t url_size = abla_bridge.request_url_size();
        const int64_t body_size = abla_bridge.request_body_size();
        const bool valid = identifier > 0 &&
            abla_copy_platform_request_field(
                abla_bridge.request_method_bytes,
                method_size,
                ABLA_REQUEST_METHOD_LIMIT,
                abla_bridge.request_method_byte
            ) &&
            abla_copy_platform_request_field(
                abla_bridge.request_url,
                url_size,
                ABLA_REQUEST_URL_LIMIT,
                abla_bridge.request_url_byte
            ) &&
            abla_copy_platform_request_field(
                abla_bridge.request_body,
                body_size,
                ABLA_REQUEST_BODY_LIMIT,
                abla_bridge.request_body_byte
            );
        abla_bridge.request_pop();
        if (!valid) {
            abla_publish_failure(
                ABLA_FAILURE_PROTOCOL,
                "libabla_app.so produced an invalid request"
            );
            return;
        }
        abla_publish_request(identifier, method_size, url_size, body_size);
        identifier = abla_bridge.request_next_id();
    }
}

static int64_t abla_host_callback(void *context, int64_t value) {
    (void)context;
    if (value == ABLA_TREE_BEGIN) {
        abla_bridge.tree_size = 0;
        abla_bridge.tree_valid = true;
        return 0;
    }
    if (value >= ABLA_TREE_BYTE_BASE &&
        value <= ABLA_TREE_BYTE_BASE + 255) {
        if (!abla_tree_append((uint8_t)(value - ABLA_TREE_BYTE_BASE))) {
            abla_bridge.tree_valid = false;
            return -1;
        }
        return 0;
    }
    if (value == ABLA_TREE_END) {
        if (!abla_bridge.tree_valid) {
            abla_publish_failure(
                ABLA_FAILURE_PROTOCOL,
                "Abla UI tree exceeded its native byte/allocation limit"
            );
            return -1;
        }
        abla_publish_tree();
        return 0;
    }
    if (value == ABLA_WAIT_EVENT) {
        abla_drain_platform_effects();
        abla_drain_platform_requests();
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
        abla_publish_failure(ABLA_FAILURE_STARTUP, dlerror());
        return NULL;
    }
    AblaMobileRun run_checked = (AblaMobileRun)dlsym(
        library,
        "abla_mobile_run_checked"
    );
    AblaMobileRun failure_size = (AblaMobileRun)dlsym(
        library,
        "abla_mobile_failure_size"
    );
    AblaFailureByte failure_byte = (AblaFailureByte)dlsym(
        library,
        "abla_mobile_failure_byte"
    );
    if (run_checked == NULL || failure_size == NULL || failure_byte == NULL) {
        abla_publish_failure(
            ABLA_FAILURE_STARTUP,
            "libabla_app.so is missing checked panic containment ABI"
        );
        dlclose(library);
        return NULL;
    }
    AblaPlatformAttach attach = (AblaPlatformAttach)dlsym(
        library,
        "abla_mobile_platform_attach"
    );
    if (attach == NULL || attach(abla_host_callback, &abla_bridge) != 1) {
        abla_publish_failure(
            ABLA_FAILURE_STARTUP,
            "could not attach the Abla Mobile host runtime"
        );
        dlclose(library);
        return NULL;
    }
    abla_bridge.effect_next_kind = (AblaEffectNext)dlsym(
        library,
        "abla_mobile_platform_effect_next_kind"
    );
    abla_bridge.effect_next_size = (AblaEffectNext)dlsym(
        library,
        "abla_mobile_platform_effect_next_size"
    );
    abla_bridge.effect_next_byte = (AblaEffectByte)dlsym(
        library,
        "abla_mobile_platform_effect_next_byte"
    );
    abla_bridge.effect_pop = (AblaEffectPop)dlsym(
        library,
        "abla_mobile_platform_effect_pop"
    );
    abla_bridge.request_next_id = (AblaRequestNext)dlsym(
        library, "abla_mobile_platform_request_next_id"
    );
    abla_bridge.request_method_size = (AblaRequestNext)dlsym(
        library, "abla_mobile_platform_request_method_size"
    );
    abla_bridge.request_method_byte = (AblaRequestByte)dlsym(
        library, "abla_mobile_platform_request_method_byte"
    );
    abla_bridge.request_url_size = (AblaRequestNext)dlsym(
        library, "abla_mobile_platform_request_url_size"
    );
    abla_bridge.request_url_byte = (AblaRequestByte)dlsym(
        library, "abla_mobile_platform_request_url_byte"
    );
    abla_bridge.request_body_size = (AblaRequestNext)dlsym(
        library, "abla_mobile_platform_request_body_size"
    );
    abla_bridge.request_body_byte = (AblaRequestByte)dlsym(
        library, "abla_mobile_platform_request_body_byte"
    );
    abla_bridge.request_pop = (AblaRequestPop)dlsym(
        library, "abla_mobile_platform_request_pop"
    );
    if (abla_bridge.effect_next_kind == NULL ||
        abla_bridge.effect_next_size == NULL ||
        abla_bridge.effect_next_byte == NULL ||
        abla_bridge.effect_pop == NULL ||
        abla_bridge.request_next_id == NULL ||
        abla_bridge.request_method_size == NULL ||
        abla_bridge.request_method_byte == NULL ||
        abla_bridge.request_url_size == NULL ||
        abla_bridge.request_url_byte == NULL ||
        abla_bridge.request_body_size == NULL ||
        abla_bridge.request_body_byte == NULL ||
        abla_bridge.request_pop == NULL) {
        abla_publish_failure(
            ABLA_FAILURE_STARTUP,
            "libabla_app.so is missing platform service ABI"
        );
        dlclose(library);
        return NULL;
    }
    pthread_mutex_lock(&abla_bridge.mutex);
    abla_bridge.accepting_events = true;
    pthread_mutex_unlock(&abla_bridge.mutex);
    const int64_t run_status = run_checked();
    pthread_mutex_lock(&abla_bridge.mutex);
    abla_bridge.accepting_events = false;
    pthread_mutex_unlock(&abla_bridge.mutex);
    if (run_status == 1) {
        const int64_t size = failure_size();
        char message[1025];
        if (size < 0 || size > 1024) {
            abla_publish_failure(
                ABLA_FAILURE_RUNTIME_PANIC,
                "Abla runtime panic had an invalid diagnostic"
            );
        } else {
            bool valid = true;
            for (int64_t index = 0; index < size; ++index) {
                const int64_t byte = failure_byte(index);
                if (byte < 0 || byte > 255) {
                    valid = false;
                    break;
                }
                message[index] = (char)byte;
            }
            message[size] = '\0';
            abla_publish_failure(
                ABLA_FAILURE_RUNTIME_PANIC,
                valid && size > 0 ? message : "The Abla runtime panicked"
            );
        }
    } else if (run_status == 0) {
        abla_publish_failure(
            ABLA_FAILURE_UNEXPECTED_RETURN,
            "abla_mobile_run returned unexpectedly"
        );
    } else {
        abla_publish_failure(
            ABLA_FAILURE_UNEXPECTED_RETURN,
            "invalid checked Abla runtime result"
        );
    }
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
    abla_bridge.effect_method = (*environment)->GetStaticMethodID(
        environment,
        abla_bridge.bridge_class,
        "onEffectFromNative",
        "(I[B)V"
    );
    abla_bridge.request_callback_method = (*environment)->GetStaticMethodID(
        environment,
        abla_bridge.bridge_class,
        "onHttpRequestFromNative",
        "(J[B[B[B)V"
    );
    abla_bridge.failure_method = (*environment)->GetStaticMethodID(
        environment,
        abla_bridge.bridge_class,
        "onFailureFromNative",
        "(I[B)V"
    );
    if (abla_bridge.effect_method == NULL ||
        abla_bridge.request_callback_method == NULL ||
        abla_bridge.failure_method == NULL) {
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
    if (!abla_bridge.started || !abla_bridge.accepting_events ||
        abla_bridge.event_size >= ABLA_EVENT_LIMIT) {
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
