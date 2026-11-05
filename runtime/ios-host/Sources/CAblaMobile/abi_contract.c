#include "abla_mobile_app.h"
/* Host-side contract, independent of a linked application. The production
 * bridge also checks the application archive's ios_abi_version before start. */
int64_t abla_ios_host_contract_v1(void) { return ABLA_MOBILE_IOS_ABI_VERSION; }
