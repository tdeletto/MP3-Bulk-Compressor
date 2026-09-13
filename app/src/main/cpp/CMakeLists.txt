cmake_minimum_required(VERSION 3.22.1)
project("mp3bulk" C)

file(GLOB LAME_SOURCES ${CMAKE_SOURCE_DIR}/lame/*.c)

add_library(mp3lame STATIC ${LAME_SOURCES})
target_include_directories(mp3lame PRIVATE ${CMAKE_SOURCE_DIR} ${CMAKE_SOURCE_DIR}/lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame PRIVATE -O3 -ffast-math -fvisibility=hidden -w)

add_library(lamejni SHARED lame_jni.c)
target_include_directories(lamejni PRIVATE ${CMAKE_SOURCE_DIR}/lame)
target_compile_options(lamejni PRIVATE -O2 -Wall)
target_link_libraries(lamejni mp3lame log)
target_link_options(lamejni PRIVATE -Wl,-z,max-page-size=16384)
