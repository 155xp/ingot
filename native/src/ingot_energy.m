// Power state and low-power waiting for the energy policy and the frame limiter.
#import "ingot.h"
#import "ingot_internal.h"

#import <IOKit/ps/IOPowerSources.h>
#import <IOKit/ps/IOPSKeys.h>
#include <errno.h>
#include <mach/mach_time.h>
#include <notify.h>
#include <sys/event.h>
#include <unistd.h>

// The providing power source only changes when IOKit posts kIOPSNotifyPowerSource, so the IOPS snapshot (an IPC to
// powerd) is only taken again after that notification; notify_check itself reads shared memory.
static os_unfair_lock power_lock = OS_UNFAIR_LOCK_INIT;
static int power_token = NOTIFY_TOKEN_INVALID;
static bool power_known;
static bool power_on_battery;

static bool query_on_battery(void) {
    CFTypeRef info = IOPSCopyPowerSourcesInfo();
    if (info == NULL) {
        return false;
    }
    CFStringRef type = IOPSGetProvidingPowerSourceType(info);
    bool battery = type != NULL && CFStringCompare(type, CFSTR(kIOPMBatteryPowerKey), 0) == kCFCompareEqualTo;
    CFRelease(info);
    return battery;
}

static bool on_battery(void) {
    os_unfair_lock_lock(&power_lock);
    if (power_token == NOTIFY_TOKEN_INVALID && notify_register_check(kIOPSNotifyPowerSource, &power_token) != NOTIFY_STATUS_OK) {
        power_token = NOTIFY_TOKEN_INVALID;
    }
    int changed = 1;
    if (power_token != NOTIFY_TOKEN_INVALID && notify_check(power_token, &changed) != NOTIFY_STATUS_OK) {
        changed = 1;
    }
    if (changed || !power_known) {
        power_on_battery = query_on_battery();
        power_known = true;
    }
    bool battery = power_on_battery;
    os_unfair_lock_unlock(&power_lock);
    return battery;
}

INGOT_EXPORT uint32_t ingot_power_state(void) {
    @autoreleasepool {
        NSProcessInfo *process = NSProcessInfo.processInfo;
        uint32_t state = 0;
        if (process.lowPowerModeEnabled) {
            state |= INGOT_POWER_LOW_POWER_MODE;
        }
        if (on_battery()) {
            state |= INGOT_POWER_ON_BATTERY;
        }
        state |= ((uint32_t)process.thermalState & 0xFFu) << 8;
        return state;
    }
}

// Each waiting thread owns a kqueue (closed when the thread exits). The key stores fd + 1 so that 0 means "none".
static pthread_key_t wait_queue_key;
static pthread_once_t wait_queue_once = PTHREAD_ONCE_INIT;

static void close_wait_queue(void *value) {
    close((int)((intptr_t)value - 1));
}

static void create_wait_queue_key(void) {
    pthread_key_create(&wait_queue_key, close_wait_queue);
}

static int thread_wait_queue(void) {
    pthread_once(&wait_queue_once, create_wait_queue_key);
    intptr_t stored = (intptr_t)pthread_getspecific(wait_queue_key);
    if (stored != 0) {
        return (int)(stored - 1);
    }
    int queue = kqueue();
    if (queue >= 0) {
        pthread_setspecific(wait_queue_key, (void *)(intptr_t)(queue + 1));
    }
    return queue;
}

// Sleeps on an absolute mach-time kqueue timer with NOTE_CRITICAL, which exempts this one wake-up from timer
// coalescing. Measured on an M4 Pro (macOS 27.2): mach_wait_until and uncritical timers woke about half the interval
// late (~0.5 ms on 1 ms, ~4 ms on 8 ms waits) because the kernel applies coalescing leeway proportional to the
// interval; NOTE_CRITICAL wakes ~25 us after the deadline. One precise wake-up per frame costs no more energy than a
// coalesced one, and far less than spinning out the remainder like vanilla's limiter does.
INGOT_EXPORT void ingot_wait_until(int64_t deadline_ns) {
    uint64_t deadline = ingot_ns_to_ticks_ceil(deadline_ns);
    if (mach_absolute_time() >= deadline) {
        return;
    }
    int queue = thread_wait_queue();
    // Loop on the clock, not on the event: an interrupted wait or a stale event from an earlier interrupted wait
    // re-arms the timer (EV_ADD modifies the existing one) instead of returning early.
    while (queue >= 0 && mach_absolute_time() < deadline) {
        struct kevent64_s timer = {
            .ident = 1,
            .filter = EVFILT_TIMER,
            .flags = EV_ADD | EV_ONESHOT,
            .fflags = NOTE_ABSOLUTE | NOTE_MACHTIME | NOTE_CRITICAL,
            .data = (int64_t)deadline,
        };
        struct kevent64_s fired;
        int count = kevent64(queue, &timer, 1, &fired, 1, 0, NULL);
        // A timer the kernel rejects comes back as an EV_ERROR event rather than a failed call; re-arming it would
        // spin until the deadline, so both kinds of failure fall back.
        if ((count < 0 && errno != EINTR) || (count > 0 && (fired.flags & EV_ERROR) != 0)) {
            break;
        }
    }
    // Without a kqueue (or if the timer fails) fall back to mach_wait_until, which only returns early when interrupted.
    while (mach_absolute_time() < deadline) {
        if (mach_wait_until(deadline) == KERN_SUCCESS) {
            break;
        }
    }
}

INGOT_EXPORT int64_t ingot_now_ns(void) {
    return ingot_ticks_to_ns(mach_absolute_time());
}
