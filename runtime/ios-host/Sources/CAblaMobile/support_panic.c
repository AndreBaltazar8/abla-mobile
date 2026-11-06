/* Preserve Android's shared support unchanged. On iOS the checked boundary
 * covers application-owned ABI query/global initialization as well as run. */
#define abla_mobile_run abla_ios_checked_entry
#include "../../../native/abla_mobile_panic.c"
#undef abla_mobile_run
