// Declarations shared between the native translation units; not part of the Java-facing ABI.
#pragma once

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

void ingot_set_error(NSString *message);
