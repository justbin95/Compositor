cmake_minimum_required(VERSION 3.22.1)
project(compositor_editor LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(BRUSH_PIXELS_DIR "${REPOSITORY_ROOT}/Compositor/Rendering")

add_library(compositor_pixel_algorithms STATIC
    "${BRUSH_PIXELS_DIR}/BrushPixels.c"
)
target_include_directories(compositor_pixel_algorithms PUBLIC "${BRUSH_PIXELS_DIR}")
target_compile_options(compositor_pixel_algorithms PRIVATE -Wall -Wextra)

add_library(compositor_editor SHARED pixel_jni.cpp)
target_link_libraries(compositor_editor PRIVATE compositor_pixel_algorithms)
target_compile_options(compositor_editor PRIVATE -Wall -Wextra)
