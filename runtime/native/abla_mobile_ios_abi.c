#include <stdint.h>
/* Mobile-owned marker included in each static iOS slice. Changes to the
 * platform copy/queue contract require a new version and a host refusal. */
int64_t abla_mobile_ios_abi_version(void) { return 1; }
