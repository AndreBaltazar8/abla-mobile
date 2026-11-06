#ifndef ABLA_IOS_APPLICATION_H
#define ABLA_IOS_APPLICATION_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define ABLA_IOS_APPLICATION_PROTOCOL_VERSION 1
/* Call only through the host's checked boundary on its runtime thread. Even
 * the version query may initialize Abla globals. No Abla pointers escape. */
int64_t abla_mobile_application_protocol_version(void);
int64_t abla_mobile_run(void);
#ifdef __cplusplus
}
#endif
#endif
