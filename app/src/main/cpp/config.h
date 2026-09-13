/* Minimal LAME config for Android (bionic libc). */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_MEMORY_H 1

#define SIZEOF_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_LONG_LONG 8
#define SIZEOF_FLOAT 4
#define SIZEOF_DOUBLE 8
#define HAVE_IEEE754_FLOAT32_T 0
#define HAVE_IEEE854_FLOAT80 0

#include <stdint.h>
typedef float ieee754_float32_t;
typedef double ieee754_float64_t;

#define PACKAGE "lame"
#define VERSION "3.100"
#define PROTOTYPES 1
#define USE_FAST_LOG 1
#define TAKEHIRO_IEEE754_HACK 1

#endif
